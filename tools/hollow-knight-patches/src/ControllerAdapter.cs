using System.Collections.Generic;
using UnityEngine.InputSystem;

namespace HollowKnightPatches
{
    // The shared gamepad only speaks Unity Input System. HK supplies its InControl binding here.
    public static class ControllerAdapter
    {
        static readonly Dictionary<Gamepad, InControl.NewUnityInputDevice> Owned = new Dictionary<Gamepad, InControl.NewUnityInputDevice>();
        public static void Ensure(Gamepad pad)
        {
            if (!InControl.InputManager.IsSetup) return;
            foreach (var device in InControl.InputManager.Devices)
                if (device is InControl.NewUnityInputDevice binding && binding.IsAttached && binding.UnityGamepad == pad) return;
            var added = new InControl.NewUnityInputDevice(pad);
            InControl.InputManager.AttachDevice(added);
            Owned[pad] = added;
        }
        public static void Remove(Gamepad pad)
        {
            if (!Owned.TryGetValue(pad, out var binding)) return;
            if (binding.IsAttached) InControl.InputManager.DetachDevice(binding);
            Owned.Remove(pad);
        }
    }
}
