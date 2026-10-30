#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <sqlite3.h>
#include <lz4.h>
#include <zstd.h>
#include <ogg/ogg.h>
#include <vorbis/codec.h>
#include <vorbis/vorbisenc.h>
#include <vorbis/vorbisfile.h>
#include <png.h>
#define REQUIRE(test) do { if (!(test)) { fprintf(stderr, "Failed: %s:%d: %s\n", __FILE__, __LINE__, #test); return 1; } } while (0)

static int database(void) {
    sqlite3 *db = NULL; sqlite3_stmt *stmt = NULL;
    REQUIRE(sqlite3_open(":memory:", &db) == SQLITE_OK);
    REQUIRE(sqlite3_exec(db, "CREATE TABLE sample(value TEXT); INSERT INTO sample VALUES('Android');", NULL, NULL, NULL) == SQLITE_OK);
    REQUIRE(sqlite3_prepare_v2(db, "SELECT value FROM sample", -1, &stmt, NULL) == SQLITE_OK);
    REQUIRE(sqlite3_step(stmt) == SQLITE_ROW);
    REQUIRE(strcmp((const char *)sqlite3_column_text(stmt, 0), "Android") == 0);
    sqlite3_finalize(stmt); REQUIRE(sqlite3_close(db) == SQLITE_OK); return 0;
}
static int compress_data(int zstd) {
    char input[4096], output[8192], restored[4096];
    for (int i=0; i<4096; i++) input[i] = (char)(i % 31);
    if (zstd) {
        size_t size = ZSTD_compress(output, sizeof(output), input, sizeof(input), 3);
        REQUIRE(!ZSTD_isError(size));
        REQUIRE(ZSTD_decompress(restored, sizeof(restored), output, size) == sizeof(input));
        REQUIRE(ZSTD_isError(ZSTD_decompress(restored, sizeof(restored), "invalid", 7)));
    } else {
        int size = LZ4_compress_default(input, output, sizeof(input), sizeof(output));
        REQUIRE(size > 0);
        REQUIRE(LZ4_decompress_safe(output, restored, size, sizeof(restored)) == sizeof(input));
        REQUIRE(LZ4_decompress_safe(output, restored, size-1, sizeof(restored)) < 0);
    }
    REQUIRE(memcmp(input, restored, sizeof(input)) == 0); return 0;
}
static int container(void) {
    ogg_stream_state encoder, decoder; ogg_page page; ogg_packet packet;
    unsigned char bytes[] = "packet fixture";
    REQUIRE(ogg_stream_init(&encoder, 42) == 0 && ogg_stream_init(&decoder, 42) == 0);
    memset(&packet, 0, sizeof(packet)); packet.packet=bytes; packet.bytes=sizeof(bytes); packet.b_o_s=1;
    REQUIRE(ogg_stream_packetin(&encoder, &packet) == 0);
    REQUIRE(ogg_stream_flush(&encoder, &page) == 1);
    REQUIRE(ogg_stream_pagein(&decoder, &page) == 0 && ogg_stream_packetout(&decoder, &packet) == 1);
    REQUIRE(packet.bytes == sizeof(bytes) && memcmp(packet.packet, bytes, sizeof(bytes)) == 0);
    ogg_stream_clear(&encoder); ogg_stream_clear(&decoder); return 0;
}
static void page_to_file(FILE *file, ogg_page *page) {
    fwrite(page->header, 1, page->header_len, file); fwrite(page->body, 1, page->body_len, file);
}
static int audio(void) {
    vorbis_info info; vorbis_comment comment; vorbis_dsp_state dsp; vorbis_block block;
    ogg_stream_state stream; ogg_packet packet, h1, h2, h3; ogg_page page;
    FILE *file = tmpfile(); REQUIRE(file != NULL);
    vorbis_info_init(&info); REQUIRE(vorbis_encode_init_vbr(&info, 1, 44100, 0.3f) == 0);
    vorbis_comment_init(&comment); REQUIRE(vorbis_analysis_init(&dsp, &info) == 0);
    REQUIRE(vorbis_block_init(&dsp, &block) == 0 && ogg_stream_init(&stream, 42) == 0);
    REQUIRE(vorbis_analysis_headerout(&dsp, &comment, &h1, &h2, &h3) == 0);
    ogg_stream_packetin(&stream, &h1); ogg_stream_packetin(&stream, &h2); ogg_stream_packetin(&stream, &h3);
    while (ogg_stream_flush(&stream, &page)) page_to_file(file, &page);
    float **pcm = vorbis_analysis_buffer(&dsp, 2048);
    for (int i=0; i<2048; i++) pcm[0][i] = (float)(0.2*sin(i * 0.1));
    vorbis_analysis_wrote(&dsp, 2048); vorbis_analysis_wrote(&dsp, 0);
    while (vorbis_analysis_blockout(&dsp, &block) == 1) {
        REQUIRE(vorbis_analysis(&block, NULL) == 0 && vorbis_bitrate_addblock(&block) == 0);
        while (vorbis_bitrate_flushpacket(&dsp, &packet)) {
            ogg_stream_packetin(&stream, &packet);
            while (ogg_stream_pageout(&stream, &page)) page_to_file(file, &page);
        }
    }
    while (ogg_stream_flush(&stream, &page)) page_to_file(file, &page);
    ogg_stream_clear(&stream); vorbis_block_clear(&block); vorbis_dsp_clear(&dsp); vorbis_comment_clear(&comment); vorbis_info_clear(&info);
    rewind(file); OggVorbis_File decoded; REQUIRE(ov_open(file, &decoded, NULL, 0) == 0);
    REQUIRE(ov_info(&decoded, -1)->channels == 1 && ov_info(&decoded, -1)->rate == 44100);
    long frames, total=0; float **samples; int section;
    while ((frames=ov_read_float(&decoded, &samples, 1024, &section)) > 0) total += frames;
    REQUIRE(frames == 0 && total == 2048); ov_clear(&decoded); return 0;
}
static int image(void) {
    png_image source, dest; memset(&source, 0, sizeof(source)); source.version=PNG_IMAGE_VERSION;
    source.width=2; source.height=2; source.format=PNG_FORMAT_RGBA;
    unsigned char pixels[16] = {255,0,0,255, 0,255,0,255, 0,0,255,255, 255,255,255,255};
    png_alloc_size_t size=0;
    REQUIRE(png_image_write_to_memory(&source, NULL, &size, 0, pixels, 0, NULL));
    void *data=malloc(size); REQUIRE(data != NULL && png_image_write_to_memory(&source, data, &size, 0, pixels, 0, NULL));
    memset(&dest, 0, sizeof(dest)); dest.version=PNG_IMAGE_VERSION;
    REQUIRE(png_image_begin_read_from_memory(&dest, data, size)); dest.format=PNG_FORMAT_RGBA;
    unsigned char decoded[16]; REQUIRE(dest.width == 2 && dest.height == 2 && png_image_finish_read(&dest, NULL, decoded, 0, NULL));
    REQUIRE(memcmp(pixels, decoded, sizeof(pixels)) == 0); png_image_free(&dest); png_image_free(&source); free(data); return 0;
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    if (!strcmp(argv[1], "sqlite")) return database();
    if (!strcmp(argv[1], "lz4")) return compress_data(0);
    if (!strcmp(argv[1], "zstd")) return compress_data(1);
    if (!strcmp(argv[1], "ogg")) return container();
    if (!strcmp(argv[1], "vorbis")) { vorbis_info info; vorbis_info_init(&info); vorbis_info_clear(&info); return vorbis_version_string() ? 0 : 1; }
    if (!strcmp(argv[1], "vorbisenc") || !strcmp(argv[1], "vorbisfile")) return audio();
    if (!strcmp(argv[1], "png16")) return image();
    return 2;
}
