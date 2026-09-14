// SteamCloudClient — wraps JavaSteam's `Cloud` unified service for the
// two operations Phase 1b/1c need:
//
//   enumerateFiles(appId)            → list all CloudFile metadata
//   downloadFile(appId, filename)    → byte[] (HTTP fetch + ZIP decompress
//                                      if Steam served compressed)
//
// HTTP is via OkHttp 5 (already a transitive dep through ktor/JavaSteam).

package dev.silksong.launcher

import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_ClientBeginFileUpload_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_ClientCommitFileUpload_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_EnumerateUserFiles_Request
import `in`.dragonbra.javasteam.rpc.service.Cloud
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import com.google.protobuf.ByteString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class SteamCloudClient(session: SteamSession) {

    private val cloud: Cloud = session.steamClient
        .getHandler(SteamUnifiedMessages::class.java)!!
        .createService(Cloud::class.java)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    data class CloudFile(
        val filename: String,
        val size: Long,
        val timestampUnix: Long,
    )

    fun enumerateFiles(appId: Int): List<CloudFile> {
        val all = mutableListOf<CloudFile>()
        var startIndex = 0
        val pageSize = 500
        while (true) {
            val req = CCloud_EnumerateUserFiles_Request.newBuilder()
                .setAppid(appId)
                .setStartIndex(startIndex)
                .setCount(pageSize)
                .build()
            val resp = cloud.enumerateUserFiles(req).toFuture().get(30, TimeUnit.SECONDS)
                ?: throw RuntimeException("enumerateUserFiles returned no response")
            val body = resp.body
                ?: throw RuntimeException("enumerateUserFiles response missing body")
            val files = body.filesList
            if (files.isEmpty()) break
            for (f in files) {
                all.add(
                    CloudFile(
                        filename = f.filename,
                        size = f.fileSize.toLong(),
                        timestampUnix = f.timestamp,
                    )
                )
            }
            startIndex += files.size
            if (files.size < pageSize) break
        }
        return all
    }

    fun downloadFile(appId: Int, filename: String): ByteArray {
        val req = CCloud_ClientFileDownload_Request.newBuilder()
            .setAppid(appId)
            .setFilename(filename)
            .build()
        val resp = cloud.clientFileDownload(req).toFuture().get(30, TimeUnit.SECONDS)
            ?: throw RuntimeException("clientFileDownload returned no response")
        val body = resp.body
            ?: throw RuntimeException("clientFileDownload response missing body")

        if (body.urlHost.isNullOrEmpty())
            throw RuntimeException("Cloud download for $filename: no URL")

        val scheme = if (body.useHttps) "https" else "http"
        val url = scheme + "://" + body.urlHost + body.urlPath

        val httpReqBuilder = Request.Builder().url(url)
        for (h in body.requestHeadersList) {
            httpReqBuilder.addHeader(h.name, h.value)
        }
        val httpResp = http.newCall(httpReqBuilder.build()).execute()
        try {
            if (!httpResp.isSuccessful)
                throw RuntimeException("HTTP ${httpResp.code} fetching $filename")
            val raw = httpResp.body?.bytes() ?: ByteArray(0)
            val rawFileSize = body.rawFileSize
            val fileSize = body.fileSize
            val isZipped = rawFileSize > 0 &&
                rawFileSize != fileSize &&
                raw.size >= 4 &&
                raw[0] == 0x50.toByte() &&
                raw[1] == 0x4B.toByte() &&
                raw[2] == 0x03.toByte() &&
                raw[3] == 0x04.toByte()

            val content = if (isZipped) decompressZip(raw) else raw
            if (rawFileSize > 0 && content.size.toLong() != rawFileSize) {
                throw RuntimeException(
                    "Cloud download for $filename has wrong size: expected $rawFileSize, got ${content.size}"
                )
            }
            return content
        } finally {
            httpResp.close()
        }
    }

    private fun decompressZip(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry
                ?: throw RuntimeException("ZIP archive has no entries")
            val out = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(4096))
            val buf = ByteArray(8192)
            while (true) {
                val n = zip.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun uploadFile(
        appId: Int,
        cloudPath: String,
        rawContent: ByteArray,
        timestampUnix: Long,
    ) {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(rawContent)
        val shaBytes = ByteString.copyFrom(sha1)
        val (uploadBytes, _) = CloudCompression.compress(rawContent)

        val beginReq = CCloud_ClientBeginFileUpload_Request.newBuilder()
            .setAppid(appId)
            .setFilename(cloudPath)
            .setFileSize(uploadBytes.size)
            .setRawFileSize(rawContent.size)
            .setFileSha(shaBytes)
            .setTimeStamp(timestampUnix)
            .setCanEncrypt(false)
            .setIsSharedFile(false)
            .build()

        val beginResp = cloud.clientBeginFileUpload(beginReq)
            .toFuture().get(30, TimeUnit.SECONDS)
            ?: throw RuntimeException("clientBeginFileUpload returned no response")
        val begin = beginResp.body
            ?: throw RuntimeException("clientBeginFileUpload response missing body")

        var allBlocksOk = false
        var commitFailure: Throwable? = null
        val octetStream = "application/octet-stream".toMediaType()
        try {
            for (block in begin.blockRequestsList) {
                val scheme = if (block.useHttps) "https" else "http"
                val url = scheme + "://" + block.urlHost + block.urlPath

                val body = if (block.explicitBodyData != null && block.explicitBodyData.size() > 0) {
                    block.explicitBodyData.toByteArray()
                } else {
                    val from = block.blockOffset.toInt()
                    val to = from + block.blockLength
                    uploadBytes.sliceArray(from until to)
                }

                val reqBuilder = Request.Builder().url(url)
                for (h in block.requestHeadersList) {
                    reqBuilder.addHeader(h.name, h.value)
                }

                val rb = body.toRequestBody(octetStream)
                if (block.httpMethod == 2) reqBuilder.post(rb) else reqBuilder.put(rb)

                val resp = http.newCall(reqBuilder.build()).execute()
                resp.use {
                    if (!it.isSuccessful)
                        throw RuntimeException("HTTP ${it.code} uploading block of $cloudPath")
                }
            }
            allBlocksOk = true
        } finally {
            val commitReq = CCloud_ClientCommitFileUpload_Request.newBuilder()
                .setAppid(appId)
                .setFilename(cloudPath)
                .setFileSha(shaBytes)
                .setTransferSucceeded(allBlocksOk)
                .build()
            try {
                cloud.clientCommitFileUpload(commitReq).toFuture().get(30, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                commitFailure = t
                LauncherLog.log("Cloud commit failed for $cloudPath: ${t.message}")
            }
        }

        if (!allBlocksOk)
            throw RuntimeException("Cloud upload failed for $cloudPath")
        commitFailure?.let {
            throw RuntimeException("Cloud commit failed for $cloudPath", it)
        }
    }

    /**
     * Historical save preservation policy.
     *
     * Earlier builds deleted remote files that were absent locally. That is not
     * safe for Silksong: version-stamped userN_<version>.dat files and rotating
     * .bak files are useful restore points even after the local game has pruned
     * them. Keep the API surface so existing CloudSync code does not need a
     * risky large rewrite, but deliberately make deletion a no-op.
     */
    fun deleteFile(appId: Int, cloudPath: String) {
        LauncherLog.log("Cloud preserve: keeping remote-only save file instead of deleting ${cloudPath.substringAfterLast('/')}")
    }
}
