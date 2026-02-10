package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SnakeRunner implements Runnable {
  private final Snake snake;
  private final Board board;
  private final PauseController pauseController;
  private final List<Snake> allSnakes;
  private final int snakeIndex;
  private final AtomicBoolean gameOver;
  private final IntConsumer onGameOver;
  private final int baseSleepMs = 80;
  private final int turboSleepMs = 40;
  private int turboTicks = 0;

  public SnakeRunner(
      Snake snake,
      Board board,
      PauseController pauseController,
      List<Snake> allSnakes,
      int snakeIndex,
      AtomicBoolean gameOver,
      IntConsumer onGameOver) {
    this.snake = snake;
    this.board = board;
    this.pauseController = pauseController;
    this.allSnakes = allSnakes;
    this.snakeIndex = snakeIndex;
    this.gameOver = gameOver;
    this.onGameOver = onGameOver;
  }

  @Override
  public void run() {
    int pauseGeneration = 0;
    try {
      while (!Thread.currentThread().isInterrupted() && !gameOver.get()) {
        pauseGeneration = pauseController.awaitIfPaused(pauseGeneration);
        if (gameOver.get()) break;
        maybeTurn();
        var res = board.step(snake);
        if (res == Board.MoveResult.HIT_OBSTACLE) {
          triggerGameOver();
          break;
        } else if (res == Board.MoveResult.ATE_TURBO) {
          turboTicks = 100;
        }
        if (collidesWithAnySnake()) {
          triggerGameOver();
          break;
        }
        int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
        if (turboTicks > 0) turboTicks--;
        Thread.sleep(sleep);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void triggerGameOver() {
    if (gameOver.compareAndSet(false, true)) {
      onGameOver.accept(snakeIndex);
    }
  }

  private boolean collidesWithAnySnake() {
    Position head = snake.head();
    for (Snake other : allSnakes) {
      boolean isSelf = other == snake;
      boolean first = true;
      for (Position p : other.snapshot()) {
        if (isSelf && first) {
          first = false;
          continue;
        }
        if (head.equals(p)) return true;
        first = false;
      }
    }
    return false;
  }

  private void maybeTurn() {
    double p = (turboTicks > 0) ? 0.05 : 0.10;
    if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
  }

  private void randomTurn() {
    var dirs = Direction.values();
    snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
  }
}
