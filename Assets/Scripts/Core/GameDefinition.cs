// ===== File: Assets/Scripts/Core/GameDefinition.cs =====
using UnityEngine;

namespace LanGame.Core
{
    [CreateAssetMenu(fileName = "NewGameDefinition", menuName = "LAN Game/Core/Game Definition")]
    public class GameDefinition : ScriptableObject
    {
        [Header("Basic Information")]
        [SerializeField] private string gameId = "paint_wars";
        [SerializeField] private string displayName = "Paint Wars";
        [SerializeField] private string sceneName = "PaintWarsGame";
        [SerializeField] private Sprite icon;
        [TextArea(2, 5)]
        [SerializeField] private string description = "Territory capture game. Enclose areas to claim them!";

        [Header("Player Constraints")]
        [SerializeField] private int minPlayers = 2;
        [SerializeField] private int maxPlayers = 6;

        [Header("Default Settings")]
        [SerializeField] private int defaultMatchDurationSeconds = 180;

        public string GameId => gameId;
        public string DisplayName => displayName;
        public string SceneName => sceneName;
        public Sprite Icon => icon;
        public string Description => description;
        public int MinPlayers => minPlayers;
        public int MaxPlayers => maxPlayers;
        public int DefaultMatchDurationSeconds => defaultMatchDurationSeconds;

        public void Initialize(string id, string name, string scene, string desc, int minP, int maxP, int duration)
        {
            gameId = id;
            displayName = name;
            sceneName = scene;
            description = desc;
            minPlayers = minP;
            maxPlayers = maxP;
            defaultMatchDurationSeconds = duration;
        }
    }
}
