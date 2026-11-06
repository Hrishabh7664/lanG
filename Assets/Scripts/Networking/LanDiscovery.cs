// ===== File: Assets/Scripts/Networking/LanDiscovery.cs =====
using System;
using System.Net;
using Mirror;
using Mirror.Discovery;
using UnityEngine;

namespace LanGame.Networking
{
    [DisallowMultipleComponent]
    public class LanDiscovery : NetworkDiscoveryBase<DiscoveryRequestMessage, DiscoveryResponseMessage>
    {
        public event Action<DiscoveryResponseMessage, IPEndPoint> OnRoomDiscovered;

        #if UNITY_ANDROID && !UNITY_EDITOR
        private AndroidJavaObject multicastLock;
        #endif

        public override void Start()
        {
            base.Start();
            AcquireMulticastLock();
        }

        private void OnDestroy()
        {
            ReleaseMulticastLock();
        }

        private void AcquireMulticastLock()
        {
            #if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using (AndroidJavaClass playerClass = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (AndroidJavaObject activity = playerClass.GetStatic<AndroidJavaObject>("currentActivity"))
                using (AndroidJavaObject wifiManager = activity.Call<AndroidJavaObject>("getSystemService", "wifi"))
                {
                    if (wifiManager != null)
                    {
                        multicastLock = wifiManager.Call<AndroidJavaObject>("createMulticastLock", "LanGameMulticastLock");
                        multicastLock.Call("acquire");
                        Debug.Log("[LanDiscovery] Android Multicast Lock acquired.");
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"[LanDiscovery] Failed to acquire Multicast Lock: {ex.Message}");
            }
            #endif
        }

        private void ReleaseMulticastLock()
        {
            #if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                if (multicastLock != null && multicastLock.Call<bool>("isHeld"))
                {
                    multicastLock.Call("release");
                    multicastLock = null;
                    Debug.Log("[LanDiscovery] Android Multicast Lock released.");
                }
            }
            catch (Exception ex)
            {
                Debug.LogError($"[LanDiscovery] Failed to release Multicast Lock: {ex.Message}");
            }
            #endif
        }

        protected override DiscoveryRequestMessage GetRequest()
        {
            return new DiscoveryRequestMessage
            {
                clientVersion = Application.version
            };
        }

        protected override DiscoveryResponseMessage ProcessRequest(DiscoveryRequestMessage request, IPEndPoint endpoint)
        {
            RoomManager roomManager = RoomManager.Instance;
            int currentPlayers = NetworkServer.active ? NetworkServer.connections.Count : 0;

            return new DiscoveryResponseMessage(
                ServerId,
                roomManager != null ? roomManager.CurrentRoomName : "LAN Room",
                roomManager != null ? roomManager.CurrentHostName : "Host",
                roomManager != null ? roomManager.CurrentGameId : "paint_wars",
                currentPlayers,
                roomManager != null ? roomManager.maxConnections : 6,
                Transport.active != null ? (ushort)((kcp2k.KcpTransport)Transport.active).Port : (ushort)7777,
                Transport.active != null ? Transport.active.ServerUri() : null
            );
        }

        protected override void ProcessResponse(DiscoveryResponseMessage response, IPEndPoint endpoint)
        {
            response.uri = new UriBuilder(response.uri) { Host = endpoint.Address.ToString() }.Uri;
            OnRoomDiscovered?.Invoke(response, endpoint);
        }
    }
}
