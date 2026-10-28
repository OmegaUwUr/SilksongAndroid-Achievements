using System.Collections.Generic;
namespace InControl
{
    public class InputDevice { public bool IsAttached; }
    public class NewUnityInputDevice : InputDevice
    {
        public UnityEngine.InputSystem.Gamepad UnityGamepad;
        public NewUnityInputDevice(UnityEngine.InputSystem.Gamepad gamepad) { UnityGamepad = gamepad; }
    }
    public static class InputManager
    {
        public static bool IsSetup = true;
        public static List<InputDevice> Devices = new List<InputDevice>();
        public static void AttachDevice(InputDevice device) { device.IsAttached = true; Devices.Add(device); }
        public static void DetachDevice(InputDevice device) { device.IsAttached = false; Devices.Remove(device); }
    }
}
