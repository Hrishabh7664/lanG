// ===== File: Assets/Editor/ProjectBootstrap.cs =====
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using LanGame.Core;

namespace LanGame.EditorTools
{
    public static class ProjectBootstrap
    {
        private static readonly string[] FolderPaths = new string[]
        {
            "Assets/Scripts/Core",
            "Assets/Scripts/Networking",
            "Assets/Scripts/UI",
            "Assets/Scripts/Games/PaintWars",
            "Assets/Scripts/Games/JetStrike",
            "Assets/Scripts/Shared",
            "Assets/Editor",
            "Assets/Scenes",
            "Assets/Prefabs",
            "Assets/ScriptableObjects/Games"
        };

        private static readonly string[] SceneNames = new string[]
        {
            "Bootstrap",
            "MainMenu",
            "GameSelect",
            "CreateRoom",
            "RoomList",
            "Lobby",
            "PaintWarsGame",
            "JetStrikeGame"
        };

        [MenuItem("LAN Game/Bootstrap Project Architecture", false, 1)]
        public static void BootstrapProject()
        {
            Debug.Log("[ProjectBootstrap] Starting Automated Project Architecture Setup...");

            CreateFolderStructure();
            GameRegistry registry = CreateScriptableObjects();
            CreateScenesAndSetupBuildSettings(registry);

            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();

            Debug.Log("[ProjectBootstrap] Project Architecture Setup Completed Successfully!");
            EditorUtility.DisplayDialog("LAN Game Bootstrap", "Project structure, ScriptableObjects, and Scenes created successfully!\nAdded all scenes to Build Settings.", "OK");
        }

        private static void CreateFolderStructure()
        {
            foreach (string path in FolderPaths)
            {
                if (!Directory.Exists(path))
                {
                    Directory.CreateDirectory(path);
                    Debug.Log($"[ProjectBootstrap] Created Directory: {path}");
                }
            }
            AssetDatabase.Refresh();
        }

        private static GameRegistry CreateScriptableObjects()
        {
            // 1. Paint Wars SO
            string paintWarsPath = "Assets/ScriptableObjects/Games/PaintWarsDefinition.asset";
            GameDefinition paintWarsDef = AssetDatabase.LoadAssetAtPath<GameDefinition>(paintWarsPath);
            if (paintWarsDef == null)
            {
                paintWarsDef = ScriptableObject.CreateInstance<GameDefinition>();
                paintWarsDef.Initialize(
                    "paint_wars",
                    "Paint Wars",
                    "PaintWarsGame",
                    "Paper.io style territory conquest game. Enclose cells to claim territory and cut off opponent trails!",
                    2,
                    6,
                    180
                );
                AssetDatabase.CreateAsset(paintWarsDef, paintWarsPath);
                Debug.Log($"[ProjectBootstrap] Created GameDefinition asset: {paintWarsPath}");
            }

            // 2. Jet Strike SO
            string jetStrikePath = "Assets/ScriptableObjects/Games/JetStrikeDefinition.asset";
            GameDefinition jetStrikeDef = AssetDatabase.LoadAssetAtPath<GameDefinition>(jetStrikePath);
            if (jetStrikeDef == null)
            {
                jetStrikeDef = ScriptableObject.CreateInstance<GameDefinition>();
                jetStrikeDef.Initialize(
                    "jet_strike",
                    "Jet Strike",
                    "JetStrikeGame",
                    "Mini Militia style 2D side-scrolling shooter with jetpacks, dual sticks, and platform combat!",
                    2,
                    8,
                    300
                );
                AssetDatabase.CreateAsset(jetStrikeDef, jetStrikePath);
                Debug.Log($"[ProjectBootstrap] Created GameDefinition asset: {jetStrikePath}");
            }

            // 3. Game Registry SO
            string registryPath = "Assets/ScriptableObjects/GameRegistry.asset";
            GameRegistry registry = AssetDatabase.LoadAssetAtPath<GameRegistry>(registryPath);
            if (registry == null)
            {
                registry = ScriptableObject.CreateInstance<GameRegistry>();
                AssetDatabase.CreateAsset(registry, registryPath);
                Debug.Log($"[ProjectBootstrap] Created GameRegistry asset: {registryPath}");
            }

            registry.SetGames(new List<GameDefinition> { paintWarsDef, jetStrikeDef });
            EditorUtility.SetDirty(registry);

            return registry;
        }

        private static void CreateScenesAndSetupBuildSettings(GameRegistry registry)
        {
            List<EditorBuildSettingsScene> buildScenes = new List<EditorBuildSettingsScene>();

            foreach (string sceneName in SceneNames)
            {
                string scenePath = $"Assets/Scenes/{sceneName}.unity";
                if (!File.Exists(scenePath))
                {
                    Scene newScene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

                    if (sceneName == "Bootstrap")
                    {
                        SetupBootstrapScene(newScene, registry);
                    }

                    EditorSceneManager.SaveScene(newScene, scenePath);
                    Debug.Log($"[ProjectBootstrap] Created Scene: {scenePath}");
                }

                buildScenes.Add(new EditorBuildSettingsScene(scenePath, true));
            }

            EditorBuildSettings.scenes = buildScenes.ToArray();
            Debug.Log($"[ProjectBootstrap] Updated Build Settings with {buildScenes.Count} scenes.");
        }

        private static void SetupBootstrapScene(Scene scene, GameRegistry registry)
        {
            GameObject bootstrapObj = new GameObject("AppBootstrap");
            AppBootstrap appBootstrap = bootstrapObj.AddComponent<AppBootstrap>();

            // Wire GameRegistry reference via SerializedObject
            SerializedObject serializedAppBootstrap = new SerializedObject(appBootstrap);
            SerializedProperty registryProp = serializedAppBootstrap.FindProperty("gameRegistry");
            if (registryProp != null)
            {
                registryProp.objectReferenceValue = registry;
                serializedAppBootstrap.ApplyModifiedProperties();
            }

            GameObject loaderObj = new GameObject("SceneLoader");
            SceneLoader loader = loaderObj.AddComponent<SceneLoader>();

            // Create UI Canvas for Screen Fading
            GameObject canvasObj = new GameObject("FadeCanvas");
            Canvas canvas = canvasObj.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = 999;
            canvasObj.AddComponent<UnityEngine.UI.CanvasScaler>();
            canvasObj.AddComponent<UnityEngine.UI.GraphicRaycaster>();

            GameObject panelObj = new GameObject("FadePanel");
            panelObj.transform.SetParent(canvasObj.transform, false);
            RectTransform rect = panelObj.AddComponent<RectTransform>();
            rect.anchorMin = Vector2.zero;
            rect.anchorMax = Vector2.one;
            rect.sizeDelta = Vector2.zero;

            UnityEngine.UI.Image img = panelObj.AddComponent<UnityEngine.UI.Image>();
            img.color = Color.black;

            CanvasGroup group = panelObj.AddComponent<CanvasGroup>();
            group.alpha = 0f;
            group.blocksRaycasts = false;

            SerializedObject serializedLoader = new SerializedObject(loader);
            SerializedProperty groupProp = serializedLoader.FindProperty("fadeCanvasGroup");
            if (groupProp != null)
            {
                groupProp.objectReferenceValue = group;
                serializedLoader.ApplyModifiedProperties();
            }
        }
    }
}
