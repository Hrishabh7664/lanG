// ===== File: Assets/Scripts/Core/AppBootstrap.cs =====
using System.Collections;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace LanGame.Core
{
    public class AppBootstrap : MonoBehaviour
    {
        [Header("Registry")]
        [SerializeField] private GameRegistry gameRegistry;

        [Header("Settings")]
        [SerializeField] private int targetFrameRate = 60;
        [SerializeField] private bool preventScreenSleep = true;
        [SerializeField] private string nextSceneName = "MainMenu";

        private static AppBootstrap instance;
        public static AppBootstrap Instance => instance;

        public GameRegistry Registry => gameRegistry;

        private void Awake()
        {
            if (instance != null && instance != this)
            {
                Destroy(gameObject);
                return;
            }

            instance = this;
            DontDestroyOnLoad(gameObject);

            InitializeApplication();
        }

        private void Start()
        {
            if (!string.IsNullOrEmpty(nextSceneName))
            {
                StartCoroutine(LoadNextSceneRoutine());
            }
        }

        private void InitializeApplication()
        {
            Application.targetFrameRate = targetFrameRate;
            Screen.sleepTimeout = preventScreenSleep ? SleepTimeout.NeverSleep : SleepTimeout.SystemSetting;

            #if UNITY_ANDROID
            // Force Landscape Orientation
            Screen.orientation = ScreenOrientation.LandscapeLeft;
            Screen.autorotateToLandscapeLeft = true;
            Screen.autorotateToLandscapeRight = true;
            Screen.autorotateToPortrait = false;
            Screen.autorotateToPortraitUpsideDown = false;
            #endif

            Debug.Log("[AppBootstrap] Initialized app settings and registry successfully.");
        }

        private IEnumerator LoadNextSceneRoutine()
        {
            yield return new WaitForSeconds(0.5f);
            if (SceneLoader.Instance != null)
            {
                SceneLoader.Instance.LoadScene(nextSceneName);
            }
            else
            {
                SceneManager.LoadScene(nextSceneName);
            }
        }
    }
}
