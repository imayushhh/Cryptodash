(() => {
  const SPEED_PX_PER_SECOND = 34;
  let animationFrameId = 0;

  function updateTaglineDurations() {
    document.querySelectorAll('.tagline-wrapper .moving-text').forEach((element) => {
      const width = element.scrollWidth || element.getBoundingClientRect().width;
      if (!width) {
        return;
      }

      const durationSeconds = Math.max(12, (width * 2) / SPEED_PX_PER_SECOND);
      element.style.setProperty('--tagline-duration', `${durationSeconds.toFixed(2)}s`);
    });
  }

  function scheduleUpdate() {
    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId);
    }

    animationFrameId = requestAnimationFrame(() => {
      animationFrameId = 0;
      updateTaglineDurations();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleUpdate, { once: true });
  } else {
    scheduleUpdate();
  }

  window.addEventListener('load', scheduleUpdate);
  window.addEventListener('resize', scheduleUpdate);
  window.addEventListener('pageshow', scheduleUpdate);
  window.addEventListener('focus', scheduleUpdate);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      scheduleUpdate();
    }
  });

  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleUpdate).catch(() => {});
  }
})();
