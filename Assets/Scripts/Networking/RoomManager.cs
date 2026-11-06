// ===== File: Assets/Scripts/Networking/RoomManager.cs =====
using System;
using System.Collections;
using System.Collections.Generic;
using Mirror;
using UnityEngine;
using UnityEngine.SceneManagement;
using LanGame.Core;

namespace LanGame.Networking
{
    public class RoomManager : NetworkManager
    {
        public static new RoomManager Instance => NetworkManager.singleton as RoomManager;

        [Header("LAN Discovery")]
        [SerializeField] private LanDiscovery lanDiscovery;

        [Header("Room Setup Prefabs")]
        [SerializeField] private GameObject lobbyPlayerPrefab;
        [SerializeField] private GameObject paintWarsPlayerPrefab;
        [SerializeField] private GameObject jetStrikePlayerPrefab;

        [Header("Room Active Settings")]
        private string currentRoomName = "Fun Arcade Room";
        private string currentHostName = "Player 1";
        private string currentGameId = "paint_wars";
        private int matchDurationSeconds = 180;

        public string CurrentRoomName => currentRoomName;
        public string CurrentHostName => currentHostName;
        public string CurrentGameId => currentGameId;
        public int MatchDurationSeconds => matchDurationSeconds;

        public List<LobbyPlayer> ConnectedLobbyPlayers { get; } = new List<LobbyPlayer>();

        public event Action OnLobbyPlayersUpdated;
        public event Action OnClientConnectedToServer;
        public event Action OnClientDisconnectedFromServer;

        public override void Awake()
        {
            base.Awake();
            if (lanDiscovery == null)
            {
                lanDiscovery = GetComponent<LanDiscovery>();
            }
        }

        public void ConfigureRoom(string roomName, string hostName, string gameId, int maxPlayers, int durationSeconds)
        {
            currentRoomName = roomName;
            currentHostName = hostName;
            currentGameId = gameId;
            maxConnections = maxPlayers;
            matchDurationSeconds = durationSeconds;
        }

        public void HostRoom()
        {
            StartHost();
            if (lanDiscovery != null)
            {
                lanDiscovery.AdvertiseServer();
            }
        }

        public void JoinRoom(Uri serverUri)
        {
            if (lanDiscovery != null)
            {
                lanDiscovery.StopDiscovery();
            }
            StartClient(serverUri);
        }

        public void StartDiscovery()
        {
            if (lanDiscovery != null)
            {
                lanDiscovery.StartDiscovery();
            }
        }

        public void StopDiscovery()
        {
            if (lanDiscovery != null)
            {
                lanDiscovery.StopDiscovery();
            }
        }

        public override void OnStartHost()
        {
            base.OnStartHost();
            Debug.Log($"[RoomManager] Host Started: '{currentRoomName}' by '{currentHostName}' for game '{currentGameId}'.");
        }

        public override void OnStopHost()
        {
            base.OnStopHost();
            if (lanDiscovery != null)
            {
                lanDiscovery.StopDiscovery();
            }
            ConnectedLobbyPlayers.Clear();
            OnLobbyPlayersUpdated?.Invoke();
        }

        public override void OnServerAddPlayer(NetworkConnectionToClient conn)
        {
            string currentScene = SceneManager.GetActiveScene().name;

            if (currentScene == "Lobby")
            {
                GameObject lobbyObj = Instantiate(lobbyPlayerPrefab != null ? lobbyPlayerPrefab : playerPrefab);
                NetworkServer.AddPlayerForConnection(conn, lobbyObj);

                LobbyPlayer lobbyPlayer = lobbyObj.GetComponent<LobbyPlayer>();
                if (lobbyPlayer != null)
                {
                    bool isHostPlayer = conn.connectionId == 0;
                    lobbyPlayer.ServerInitializePlayer(
                        isHostPlayer ? currentHostName : $"Player {conn.connectionId + 1}",
                        isHostPlayer
                    );
                }
            }
            else
            {
                GameObject prefabToSpawn = playerPrefab;
                if (currentGameId == "paint_wars" && paintWarsPlayerPrefab != null)
                {
                    prefabToSpawn = paintWarsPlayerPrefab;
                }
                else if (currentGameId == "jet_strike" && jetStrikePlayerPrefab != null)
                {
                    prefabToSpawn = jetStrikePlayerPrefab;
                }

                Transform spawnPoint = GetStartPosition();
                Vector3 spawnPos = spawnPoint != null ? spawnPoint.position : Vector3.zero;
                Quaternion spawnRot = spawnPoint != null ? spawnPoint.rotation : Quaternion.identity;

                GameObject gamePlayerObj = Instantiate(prefabToSpawn, spawnPos, spawnRot);
                NetworkServer.AddPlayerForConnection(conn, gamePlayerObj);
            }
        }

        public override void OnServerDisconnect(NetworkConnectionToClient conn)
        {
            LobbyPlayer lobbyPlayer = conn.identity != null ? conn.identity.GetComponent<LobbyPlayer>() : null;
            if (lobbyPlayer != null && ConnectedLobbyPlayers.Contains(lobbyPlayer))
            {
                ConnectedLobbyPlayers.Remove(lobbyPlayer);
                OnLobbyPlayersUpdated?.Invoke();
            }

            base.OnServerDisconnect(conn);
        }

        public override void OnClientConnect()
        {
            base.OnClientConnect();
            OnClientConnectedToServer?.Invoke();
        }

        public override void OnClientDisconnect()
        {
            base.OnClientDisconnect();
            ConnectedLobbyPlayers.Clear();
            OnLobbyPlayersUpdated?.Invoke();
            OnClientDisconnectedFromServer?.Invoke();

            if (SceneManager.GetActiveScene().name != "MainMenu")
            {
                if (SceneLoader.Instance != null)
                {
                    SceneLoader.Instance.LoadScene("MainMenu");
                }
                else
                {
                    SceneManager.LoadScene("MainMenu");
                }
            }
        }

        public void RegisterLobbyPlayer(LobbyPlayer player)
        {
            if (player != null && !ConnectedLobbyPlayers.Contains(player))
            {
                ConnectedLobbyPlayers.Add(player);
                OnLobbyPlayersUpdated?.Invoke();
            }
        }

        public void UnregisterLobbyPlayer(LobbyPlayer player)
        {
            if (player != null && ConnectedLobbyPlayers.Contains(player))
            {
                ConnectedLobbyPlayers.Remove(player);
                OnLobbyPlayersUpdated?.Invoke();
            }
        }

        public bool AreAllPlayersReady()
        {
            if (ConnectedLobbyPlayers.Count == 0) return false;
            foreach (var player in ConnectedLobbyPlayers)
            {
                if (!player.IsReady && !player.IsHost) return false;
            }
            return true;
        }

        public void StartGameFromLobby()
        {
            if (!NetworkServer.active) return;

            GameDefinition targetGame = AppBootstrap.Instance != null && AppBootstrap.Instance.Registry != null
                ? AppBootstrap.Instance.Registry.GetGameById(currentGameId)
                : null;

            string targetScene = targetGame != null ? targetGame.SceneName : "PaintWarsGame";
            ServerChangeScene(targetScene);
        }
    }
}
