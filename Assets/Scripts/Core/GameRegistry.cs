// ===== File: Assets/Scripts/Core/GameRegistry.cs =====
using System.Collections.Generic;
using UnityEngine;

namespace LanGame.Core
{
    [CreateAssetMenu(fileName = "GameRegistry", menuName = "LAN Game/Core/Game Registry")]
    public class GameRegistry : ScriptableObject
    {
        [SerializeField] private List<GameDefinition> availableGames = new List<GameDefinition>();

        public IReadOnlyList<GameDefinition> AvailableGames => availableGames;

        public GameDefinition GetGameById(string id)
        {
            if (string.IsNullOrEmpty(id)) return null;
            return availableGames.Find(g => g != null && g.GameId.Equals(id, System.StringComparison.OrdinalIgnoreCase));
        }

        public GameDefinition GetGameBySceneName(string sceneName)
        {
            if (string.IsNullOrEmpty(sceneName)) return null;
            return availableGames.Find(g => g != null && g.SceneName.Equals(sceneName, System.StringComparison.OrdinalIgnoreCase));
        }

        public void RegisterGame(GameDefinition game)
        {
            if (game != null && !availableGames.Contains(game))
            {
                availableGames.Add(game);
            }
        }

        public void SetGames(List<GameDefinition> games)
        {
            availableGames = games ?? new List<GameDefinition>();
        }
    }
}
