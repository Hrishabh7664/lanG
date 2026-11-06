// ===== File: Assets/Scripts/Networking/RoomInfo.cs =====
using System;
using System.Net;
using Mirror;
using UnityEngine;

namespace LanGame.Networking
{
    [Serializable]
    public struct DiscoveryRequestMessage : NetworkMessage
    {
        public string clientVersion;
    }

    [Serializable]
    public struct DiscoveryResponseMessage : NetworkMessage
    {
        public long serverId;
        public string roomName;
        public string hostName;
        public string gameId;
        public int currentPlayers;
        public int maxPlayers;
        public ushort port;
        public Uri uri;

        public DiscoveryResponseMessage(long serverId, string roomName, string hostName, string gameId, int currentPlayers, int maxPlayers, ushort port, Uri uri)
        {
            this.serverId = serverId;
            this.roomName = roomName;
            this.hostName = hostName;
            this.gameId = gameId;
            this.currentPlayers = currentPlayers;
            this.maxPlayers = maxPlayers;
            this.port = port;
            this.uri = uri;
        }
    }

    [Serializable]
    public class RoomInfo
    {
        public string roomName;
        public string hostName;
        public string gameId;
        public int currentPlayers;
        public int maxPlayers;
        public IPEndPoint endPoint;
        public Uri uri;

        public RoomInfo(DiscoveryResponseMessage response, IPEndPoint endPoint)
        {
            this.roomName = response.roomName;
            this.hostName = response.hostName;
            this.gameId = response.gameId;
            this.currentPlayers = response.currentPlayers;
            this.maxPlayers = response.maxPlayers;
            this.endPoint = endPoint;
            this.uri = response.uri;
        }
    }
}
