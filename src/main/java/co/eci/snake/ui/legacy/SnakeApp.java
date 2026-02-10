package co.eci.snake.ui.legacy;

import co.eci.snake.concurrency.PauseController;
import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.GameState;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SnakeApp extends JFrame {

  private Board board;
  private GamePanel gamePanel;
  private final JButton actionButton;
  private final JButton restartButton;
  private final JLabel statusLabel;
  private final JLabel controlsLabel;
  private GameClock clock;
  private List<Snake> snakes = new CopyOnWriteArrayList<>();
  private PauseController pauseController;
  private ExecutorService exec;
  private GameState state = GameState.STOPPED;
  private AtomicBoolean gameOver = new AtomicBoolean(false);
  private AtomicInteger worstSnake = new AtomicInteger(-1);

  public SnakeApp() {
    super("The Snake Race");
    this.actionButton = new JButton("Iniciar");
    this.restartButton = new JButton("Reiniciar");
    this.statusLabel = new JLabel(" ");
    this.controlsLabel = new JLabel("Controles: Flechas (P1), WASD (P2), Espacio (Pausar)");

    setLayout(new BorderLayout());
    var north = new JPanel();
    north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
    north.add(statusLabel);
    north.add(controlsLabel);
    add(north, BorderLayout.NORTH);
    var south = new JPanel(new FlowLayout(FlowLayout.CENTER));
    south.add(actionButton);
    south.add(restartButton);
    add(south, BorderLayout.SOUTH);

    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    pack();
    setLocationRelativeTo(null);

    resetGame(true);

    actionButton.addActionListener((ActionEvent e) -> handleAction());
    restartButton.addActionListener((ActionEvent e) -> resetGame(false));

    setVisible(true);
  }

  private void handleAction() {
    if (gameOver.get()) return;
    switch (state) {
      case STOPPED -> startGame();
      case RUNNING -> pauseGame();
      case PAUSED -> resumeGame();
    }
  }

  private void startGame() {
    actionButton.setText("Pausar");
    state = GameState.RUNNING;
    statusLabel.setText(" ");
    clock.start();
    pauseController.resume();
  }

  private void pauseGame() {
    actionButton.setText("Reanudar");
    state = GameState.PAUSED;
    try {
      pauseController.requestPauseAndWait();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return;
    }
    clock.pause();
    statusLabel.setText(buildPauseStats());
  }

  private void resumeGame() {
    actionButton.setText("Pausar");
    state = GameState.RUNNING;
    statusLabel.setText(" ");
    clock.resume();
    pauseController.resume();
  }

  private String buildPauseStats() {
    int longestIdx = -1;
    int longestLen = -1;
    for (int i = 0; i < snakes.size(); i++) {
      int len = snakes.get(i).length();
      if (len > longestLen) {
        longestLen = len;
        longestIdx = i;
      }
    }
    String longest = (longestIdx >= 0)
        ? "Serpiente " + longestIdx + " (len=" + longestLen + ")"
        : "N/A";
    int worstIdx = worstSnake.get();
    String worst = (worstIdx >= 0)
        ? "Serpiente " + worstIdx
        : "Ninguna ha muerto";
    return "Mas larga: " + longest + " | Peor: " + worst;
  }

  private void handleGameOver(int snakeIdx) {
    worstSnake.compareAndSet(-1, snakeIdx);
    pauseController.resume();
    SwingUtilities.invokeLater(() -> {
      clock.stop();
      state = GameState.STOPPED;
      actionButton.setEnabled(false);
      actionButton.setText("Iniciar");
      restartButton.setEnabled(true);
      statusLabel.setText("Juego terminado. Perdio la serpiente " + snakeIdx);
    });
  }

  private void resetGame(boolean initial) {
    if (!initial) {
      if (gameOver != null) gameOver.set(true);
      if (pauseController != null) pauseController.resume();
      if (exec != null) exec.shutdownNow();
      if (clock != null) {
        clock.stop();
        clock.close();
      }
    }

    this.board = new Board(35, 28);
    this.snakes = new CopyOnWriteArrayList<>();
    int N = Integer.getInteger("snakes", 2);
    for (int i = 0; i < N; i++) {
      int x = 2 + (i * 3) % board.width();
      int y = 2 + (i * 2) % board.height();
      var dir = Direction.values()[i % Direction.values().length];
      snakes.add(Snake.of(x, y, dir));
    }

    this.pauseController = new PauseController(snakes.size(), true);
    this.gameOver = new AtomicBoolean(false);
    this.worstSnake = new AtomicInteger(-1);

    var oldPanel = this.gamePanel;
    this.gamePanel = new GamePanel(board, () -> List.copyOf(snakes));
    if (initial) {
      add(gamePanel, BorderLayout.CENTER);
    } else {
      if (oldPanel != null) remove(oldPanel);
      add(gamePanel, BorderLayout.CENTER);
      revalidate();
      repaint();
    }

    this.clock = new GameClock(60, () -> SwingUtilities.invokeLater(gamePanel::repaint));
    this.exec = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < snakes.size(); i++) {
      int idx = i;
      exec.submit(new SnakeRunner(
          snakes.get(i),
          board,
          pauseController,
          snakes,
          idx,
          gameOver,
          this::handleGameOver));
    }

    state = GameState.STOPPED;
    actionButton.setEnabled(true);
    actionButton.setText("Iniciar");
    restartButton.setEnabled(true);
    statusLabel.setText(" ");
    configureControls();
  }

  private void configureControls() {
    var im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    var am = gamePanel.getActionMap();
    im.clear();
    am.clear();

    im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
    am.put("pause", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        handleAction();
      }
    });

    if (snakes.isEmpty()) return;
    var player = snakes.get(0);
    im.put(KeyStroke.getKeyStroke("LEFT"), "left");
    im.put(KeyStroke.getKeyStroke("RIGHT"), "right");
    im.put(KeyStroke.getKeyStroke("UP"), "up");
    im.put(KeyStroke.getKeyStroke("DOWN"), "down");
    am.put("left", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.LEFT);
      }
    });
    am.put("right", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.RIGHT);
      }
    });
    am.put("up", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.UP);
      }
    });
    am.put("down", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.DOWN);
      }
    });

    if (snakes.size() > 1) {
      var p2 = snakes.get(1);
      im.put(KeyStroke.getKeyStroke('A'), "p2-left");
      im.put(KeyStroke.getKeyStroke('D'), "p2-right");
      im.put(KeyStroke.getKeyStroke('W'), "p2-up");
      im.put(KeyStroke.getKeyStroke('S'), "p2-down");
      am.put("p2-left", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.LEFT);
        }
      });
      am.put("p2-right", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.RIGHT);
        }
      });
      am.put("p2-up", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.UP);
        }
      });
      am.put("p2-down", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.DOWN);
        }
      });
    }
  }

  public static final class GamePanel extends JPanel {
    private final Board board;
    private final Supplier snakesSupplier;
    private final int cell = 20;

    @FunctionalInterface
    public interface Supplier {
      List<Snake> get();
    }

    public GamePanel(Board board, Supplier snakesSupplier) {
      this.board = board;
      this.snakesSupplier = snakesSupplier;
      setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 40));
      setBackground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      var g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g2.setColor(new Color(220, 220, 220));
      for (int x = 0; x <= board.width(); x++)
        g2.drawLine(x * cell, 0, x * cell, board.height() * cell);
      for (int y = 0; y <= board.height(); y++)
        g2.drawLine(0, y * cell, board.width() * cell, y * cell);

      // Obstáculos
      g2.setColor(new Color(255, 102, 0));
      for (var p : board.obstacles()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
        g2.setColor(Color.RED);
        g2.drawLine(x + 4, y + 4, x + cell - 6, y + 4);
        g2.drawLine(x + 4, y + 8, x + cell - 6, y + 8);
        g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
        g2.setColor(new Color(255, 102, 0));
      }

      // Ratones
      g2.setColor(Color.BLACK);
      for (var p : board.mice()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
        g2.setColor(Color.WHITE);
        g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
        g2.setColor(Color.BLACK);
      }

      // Teleports (flechas rojas)
      Map<Position, Position> tp = board.teleports();
      g2.setColor(Color.RED);
      for (var entry : tp.entrySet()) {
        Position from = entry.getKey();
        int x = from.x() * cell, y = from.y() * cell;
        int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
        int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Turbo (rayos)
      g2.setColor(Color.BLACK);
      for (var p : board.turbo()) {
        int x = p.x() * cell, y = p.y() * cell;
        int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
        int[] ys = { y + 2, y + 2, y + 8, y + 8, y + 16, y + 10 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Serpientes
      var snakes = snakesSupplier.get();
      int idx = 0;
      for (Snake s : snakes) {
        var body = s.snapshot().toArray(new Position[0]);
        for (int i = 0; i < body.length; i++) {
          var p = body[i];
          Color base = (idx == 0) ? new Color(0, 170, 0) : new Color(0, 160, 180);
          int shade = Math.max(0, 40 - i * 4);
          g2.setColor(new Color(
              Math.min(255, base.getRed() + shade),
              Math.min(255, base.getGreen() + shade),
              Math.min(255, base.getBlue() + shade)));
          g2.fillRect(p.x() * cell + 2, p.y() * cell + 2, cell - 4, cell - 4);
        }
        idx++;
      }
      g2.dispose();
    }
  }

  public static void launch() {
    SwingUtilities.invokeLater(SnakeApp::new);
  }
}
