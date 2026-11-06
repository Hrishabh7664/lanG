// ===== File: Assets/Editor/ProjectSettingsConfigurator.cs =====
using UnityEditor;
using UnityEditor.Build;
using UnityEngine;

namespace LanGame.EditorTools
{
    [InitializeOnLoad]
    public static class ProjectSettingsConfigurator
    {
        static ProjectSettingsConfigurator()
        {
            ApplyAndroidProjectSettings();
        }

        [MenuItem("LAN Game/Apply Recommended Android Settings", false, 2)]
        public static void ApplyAndroidProjectSettings()
        {
            Debug.Log("[ProjectSettingsConfigurator] Configuring Player Settings for Android...");

            // Application Metadata
            PlayerSettings.companyName = "LANGameStudio";
            PlayerSettings.productName = "LAN Party Arcade";
            PlayerSettings.applicationIdentifier = "com.langamestudio.lanpartyarcade";

            // Scripting Backend & Architecture
            NamedBuildTarget androidTarget = NamedBuildTarget.Android;
            PlayerSettings.SetScriptingBackend(androidTarget, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;

            // SDK Versions
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;

            // Orientation Settings
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;
            PlayerSettings.allowedAutorotateToLandscapeLeft = true;
            PlayerSettings.allowedAutorotateToLandscapeRight = true;
            PlayerSettings.allowedAutorotateToPortrait = false;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;

            // Performance & Optimization
            PlayerSettings.gpuSkinning = true;
            PlayerSettings.stripEngineCode = true;

            AssetDatabase.SaveAssets();
            Debug.Log("[ProjectSettingsConfigurator] Player Settings successfully configured: Min SDK 24, ARM64, IL2CPP, Landscape.");
        }
    }
}
