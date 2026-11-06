// ===== File: Assets/Editor/BuildScript.cs =====
using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace LanGame.EditorTools
{
    public static class BuildScript
    {
        private static readonly string OutputDirectory = "Builds/Android";
        private static readonly string ApkFileName = "LanPartyArcade.apk";

        private static readonly string[] BuildScenes = new string[]
        {
            "Assets/Scenes/Bootstrap.unity",
            "Assets/Scenes/MainMenu.unity",
            "Assets/Scenes/GameSelect.unity",
            "Assets/Scenes/CreateRoom.unity",
            "Assets/Scenes/RoomList.unity",
            "Assets/Scenes/Lobby.unity",
            "Assets/Scenes/PaintWarsGame.unity",
            "Assets/Scenes/JetStrikeGame.unity"
        };

        [MenuItem("LAN Game/Build Android APK", false, 100)]
        public static void BuildAndroid()
        {
            Debug.Log("[BuildScript] Starting Android APK Build...");

            PerformBuild(
                outputPath: Path.Combine(OutputDirectory, ApkFileName),
                keystoreName: null,
                keystorePass: null,
                keyAliasName: null,
                keyAliasPass: null
            );
        }

        public static void BuildAndroidFromCommandLine()
        {
            Debug.Log("[BuildScript] Command Line Android APK Build Started...");

            string envKeystorePass = Environment.GetEnvironmentVariable("ANDROID_KEYSTORE_PASS");
            string envKeyAliasPass = Environment.GetEnvironmentVariable("ANDROID_KEY_ALIAS_PASS");
            string envKeystoreName = Environment.GetEnvironmentVariable("ANDROID_KEYSTORE_PATH");
            string envKeyAliasName = Environment.GetEnvironmentVariable("ANDROID_KEY_ALIAS");

            string customOutputPath = GetCommandLineArg("-customBuildPath");
            if (string.IsNullOrEmpty(customOutputPath))
            {
                customOutputPath = Path.Combine(OutputDirectory, ApkFileName);
            }

            PerformBuild(
                outputPath: customOutputPath,
                keystoreName: envKeystoreName,
                keystorePass: envKeystorePass,
                keyAliasName: envKeyAliasName,
                keyAliasPass: envKeyAliasPass
            );
        }

        private static void PerformBuild(string outputPath, string keystoreName, string keystorePass, string keyAliasName, string keyAliasPass)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(outputPath));

            EditorUserBuildSettings.buildAppBundle = false;
            EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;

            if (!string.IsNullOrEmpty(keystoreName) && File.Exists(keystoreName))
            {
                PlayerSettings.Android.useCustomKeystore = true;
                PlayerSettings.Android.keystoreName = keystoreName;
                PlayerSettings.Android.keystorePass = keystorePass;
                PlayerSettings.Android.keyaliasName = keyAliasName;
                PlayerSettings.Android.keyaliasPass = keyAliasPass;
                Debug.Log($"[BuildScript] Configured Custom Keystore: {keystoreName}");
            }
            else
            {
                PlayerSettings.Android.useCustomKeystore = false;
                Debug.Log("[BuildScript] Using Default Unity Development Keystore.");
            }

            BuildPlayerOptions buildPlayerOptions = new BuildPlayerOptions
            {
                scenes = BuildScenes,
                locationPathName = outputPath,
                target = BuildTarget.Android,
                options = BuildOptions.None
            };

            BuildReport report = BuildPipeline.BuildPlayer(buildPlayerOptions);
            BuildSummary summary = report.summary;

            if (summary.result == BuildResult.Succeeded)
            {
                Debug.Log($"[BuildScript] Android APK Build SUCCEEDED! Total Size: {summary.totalSize} bytes. Output: {outputPath}");
            }
            else if (summary.result == BuildResult.Failed)
            {
                Debug.LogError($"[BuildScript] Android APK Build FAILED with {summary.totalErrors} errors!");
                foreach (var step in report.steps)
                {
                    foreach (var message in step.messages)
                    {
                        if (message.type == LogType.Error || message.type == LogType.Exception)
                        {
                            Debug.LogError($"[BuildStep Error] {message.content}");
                        }
                    }
                }
                if (Application.isBatchMode)
                {
                    EditorApplication.Exit(1);
                }
            }
        }

        private static string GetCommandLineArg(string name)
        {
            string[] args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == name && i + 1 < args.Length)
                {
                    return args[i + 1];
                }
            }
            return null;
        }
    }
}
