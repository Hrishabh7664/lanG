// ===== File: Assets/Scripts/Networking/LobbyPlayer.cs =====
using System;
using Mirror;
using UnityEngine;

namespace LanGame.Networking
{
    public class LobbyPlayer : NetworkBehaviour
    {
        [SyncVar(hook = nameof(OnDisplayNameChanged))]
        private string displayName = "Player";

        [SyncVar(hook = nameof(OnPlayerColorChanged))]
        private Color playerColor = Color.cyan;

        [SyncVar(hook = nameof(OnReadyStateChanged))]
        private bool isReady = false;

        [SyncVar(hook = nameof(OnHostStateChanged))]
        private bool isHost = false;

        [SyncVar]
        private int playerIndex = 0;

        public string DisplayName => displayName;
        public Color PlayerColor => playerColor;
        public bool IsReady => isReady;
        public bool IsHost => isHost;
        public int PlayerIndex => playerIndex;

        public static event Action OnAnyLobbyPlayerUpdated;

        private static readonly Color[] PresetColors = new Color[]
        {
            new Color(0.95f, 0.26f, 0.21f), // Red
            new Color(0.13f, 0.59f, 0.95f), // Blue
            new Color(0.30f, 0.69f, 0.31f), // Green
            new Color(1.00f, 0.76f, 0.03f), // Yellow
            new Color(0.61f, 0.15f, 0.69f), // Purple
            new Color(1.00f, 0.60f, 0.00f), // Orange
            new Color(0.00f, 0.74f, 0.83f), // Cyan
            new Color(0.91f, 0.12f, 0.39f)  // Pink
        };

        public override void OnStartClient()
        {
            base.OnStartClient();
            if (RoomManager.Instance != null)
            {
                RoomManager.Instance.RegisterLobbyPlayer(this);
            }
            OnAnyLobbyPlayerUpdated?.Invoke();
        }

        public override void OnStopClient()
        {
            base.OnStopClient();
            if (RoomManager.Instance != null)
            {
                RoomManager.Instance.UnregisterLobbyPlayer(this);
            }
            OnAnyLobbyPlayerUpdated?.Invoke();
        }

        [Server]
        public void ServerInitializePlayer(string name, bool hostStatus)
        {
            displayName = name;
            isHost = hostStatus;
            isReady = hostStatus; // Host is automatically ready

            if (RoomManager.Instance != null)
            {
                playerIndex = RoomManager.Instance.ConnectedLobbyPlayers.Count;
                playerColor = PresetColors[playerIndex % PresetColors.Length];
            }
        }

        [Command]
        public void CmdSetDisplayName(string newName)
        {
            if (string.IsNullOrWhiteSpace(newName)) return;
            displayName = newName.Trim();
        }

        [Command]
        public void CmdSetColor(Color newColor)
        {
            playerColor = newColor;
        }

        [Command]
        public void CmdSetReady(bool readyState)
        {
            if (isHost) return; // Host is fixed ready
            isReady = readyState;
        }

        [Command]
        public void CmdKickPlayer()
        {
            if (!isHost && netIdentity.connectionToClient != null)
            {
                netIdentity.connectionToClient.Disconnect();
            }
        }

        private void OnDisplayNameChanged(string oldVal, string newVal)
        {
            OnAnyLobbyPlayerUpdated?.Invoke();
        }

        private void OnPlayerColorChanged(Color oldVal, Color newVal)
        {
            OnAnyLobbyPlayerUpdated?.Invoke();
        }

        private void OnReadyStateChanged(bool oldVal, bool newVal)
        {
            OnAnyLobbyPlayerUpdated?.Invoke();
        }

        private void OnHostStateChanged(bool oldVal, bool newVal)
        {
            OnAnyLobbyPlayerUpdated?.Invoke();
        }
    }
}
