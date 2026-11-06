// ===== File: Assets/Scripts/Networking/NetworkGameManager.cs =====
using System;
using System.Collections;
using Mirror;
using UnityEngine;

namespace LanGame.Networking
{
    public enum MatchState
    {
        WaitingToStart,
        Countdown,
        InProgress,
        Ended
    }

    public class NetworkGameManager : NetworkBehaviour
    {
        private static NetworkGameManager instance;
        public static NetworkGameManager Instance => instance;

        [SyncVar(hook = nameof(OnMatchStateChanged))]
        private MatchState currentMatchState = MatchState.WaitingToStart;

        [SyncVar(hook = nameof(OnTimeRemainingChanged))]
        private int remainingTimeSeconds = 180;

        [SyncVar]
        private string winnerInfoText = "";

        public MatchState CurrentMatchState => currentMatchState;
        public int RemainingTimeSeconds => remainingTimeSeconds;
        public string WinnerInfoText => winnerInfoText;

        public event Action<MatchState> OnMatchStateUpdated;
        public event Action<int> OnTimerUpdated;
        public event Action<string> OnMatchEnded;

        private void Awake()
        {
            if (instance != null && instance != this)
            {
                Destroy(gameObject);
                return;
            }
            instance = this;
            DontDestroyOnLoad(gameObject);
        }

        public override void OnStartServer()
        {
            base.OnStartServer();
            int duration = RoomManager.Instance != null ? RoomManager.Instance.MatchDurationSeconds : 180;
            remainingTimeSeconds = duration;
            StartMatchCountdown();
        }

        [Server]
        public void StartMatchCountdown()
        {
            if (currentMatchState != MatchState.WaitingToStart) return;
            StartCoroutine(MatchLifecycleRoutine());
        }

        [Server]
        private IEnumerator MatchLifecycleRoutine()
        {
            // 1. Countdown Phase (3 seconds)
            currentMatchState = MatchState.Countdown;
            yield return new WaitForSeconds(3.0f);

            // 2. In Progress Phase
            currentMatchState = MatchState.InProgress;
            while (remainingTimeSeconds > 0 && currentMatchState == MatchState.InProgress)
            {
                yield return new WaitForSeconds(1.0f);
                remainingTimeSeconds--;
            }

            // 3. End Phase
            if (currentMatchState == MatchState.InProgress)
            {
                EndMatch("Time Limit Reached!");
            }
        }

        [Server]
        public void EndMatch(string winnerMessage)
        {
            if (currentMatchState == MatchState.Ended) return;

            currentMatchState = MatchState.Ended;
            winnerInfoText = winnerMessage;
            RpcOnMatchEndedClient(winnerMessage);
        }

        [ClientRpc]
        private void RpcOnMatchEndedClient(string winnerMessage)
        {
            OnMatchEnded?.Invoke(winnerMessage);
        }

        private void OnMatchStateChanged(MatchState oldState, MatchState newState)
        {
            OnMatchStateUpdated?.Invoke(newState);
        }

        private void OnTimeRemainingChanged(int oldTime, int newTime)
        {
            OnTimerUpdated?.Invoke(newTime);
        }
    }
}
