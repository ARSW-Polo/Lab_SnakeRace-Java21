package co.eci.snake.concurrency;

public final class PauseController {
  private final Object lock = new Object();
  private final int totalRunners;
  private boolean paused;
  private int pausedCount;
  private int pauseGeneration;

  public PauseController(int totalRunners, boolean startPaused) {
    if (totalRunners < 0) throw new IllegalArgumentException("totalRunners must be >= 0");
    this.totalRunners = totalRunners;
    if (startPaused) {
      this.paused = true;
      this.pauseGeneration = 1;
    }
  }

  public int awaitIfPaused(int lastSeenGeneration) throws InterruptedException {
    synchronized (lock) {
      if (paused && lastSeenGeneration != pauseGeneration) {
        pausedCount++;
        lastSeenGeneration = pauseGeneration;
        lock.notifyAll();
      }
      while (paused) {
        lock.wait();
      }
      return lastSeenGeneration;
    }
  }

  public void requestPauseAndWait() throws InterruptedException {
    synchronized (lock) {
      if (paused) return;
      paused = true;
      pauseGeneration++;
      if (totalRunners == 0) return;
      while (pausedCount < totalRunners) {
        lock.wait();
      }
    }
  }

  public void resume() {
    synchronized (lock) {
      if (!paused) return;
      paused = false;
      pausedCount = 0;
      lock.notifyAll();
    }
  }
}
