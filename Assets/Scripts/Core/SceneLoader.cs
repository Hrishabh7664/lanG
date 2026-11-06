// ===== File: Assets/Scripts/Core/SceneLoader.cs =====
using System;
using System.Collections;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace LanGame.Core
{
    public class SceneLoader : MonoBehaviour
    {
        private static SceneLoader instance;
        public static SceneLoader Instance => instance;

        [SerializeField] private CanvasGroup fadeCanvasGroup;
        [SerializeField] private float fadeDuration = 0.5f;

        private bool isLoading = false;
        public bool IsLoading => isLoading;

        public event Action<float> OnLoadingProgressUpdated;
        public event Action OnSceneLoadStarted;
        public event Action OnSceneLoadCompleted;

        private void Awake()
        {
            if (instance != null && instance != this)
            {
                Destroy(gameObject);
                return;
            }

            instance = this;
            DontDestroyOnLoad(gameObject);

            if (fadeCanvasGroup != null)
            {
                fadeCanvasGroup.alpha = 0f;
                fadeCanvasGroup.blocksRaycasts = false;
            }
        }

        public void LoadScene(string sceneName, Action onComplete = null)
        {
            if (isLoading)
            {
                Debug.LogWarning($"[SceneLoader] Already loading a scene. Ignored request for {sceneName}.");
                return;
            }

            StartCoroutine(LoadSceneRoutine(sceneName, onComplete));
        }

        private IEnumerator LoadSceneRoutine(string sceneName, Action onComplete)
        {
            isLoading = true;
            OnSceneLoadStarted?.Invoke();

            if (fadeCanvasGroup != null)
            {
                fadeCanvasGroup.blocksRaycasts = true;
                yield return FadeRoutine(1f);
            }

            AsyncOperation asyncOp = SceneManager.LoadSceneAsync(sceneName);
            asyncOp.allowSceneActivation = false;

            while (asyncOp.progress < 0.9f)
            {
                float progress = Mathf.Clamp01(asyncOp.progress / 0.9f);
                OnLoadingProgressUpdated?.Invoke(progress);
                yield return null;
            }

            OnLoadingProgressUpdated?.Invoke(1f);
            yield return new WaitForSeconds(0.1f);

            asyncOp.allowSceneActivation = true;
            while (!asyncOp.isDone)
            {
                yield return null;
            }

            if (fadeCanvasGroup != null)
            {
                yield return FadeRoutine(0f);
                fadeCanvasGroup.blocksRaycasts = false;
            }

            isLoading = false;
            OnSceneLoadCompleted?.Invoke();
            onComplete?.Invoke();
        }

        private IEnumerator FadeRoutine(float targetAlpha)
        {
            if (fadeCanvasGroup == null) yield break;

            float startAlpha = fadeCanvasGroup.alpha;
            float elapsed = 0f;

            while (elapsed < fadeDuration)
            {
                elapsed += Time.deltaTime;
                fadeCanvasGroup.alpha = Mathf.Lerp(startAlpha, targetAlpha, elapsed / fadeDuration);
                yield return null;
            }

            fadeCanvasGroup.alpha = targetAlpha;
        }
    }
}
