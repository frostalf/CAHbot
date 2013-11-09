package to.joe.cah;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.LongOptionHandler;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;
import org.kohsuke.args4j.spi.StringOptionHandler;
import org.pircbotx.Colors;
import org.pircbotx.PircBotX;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CardsAgainstHumanity extends PircBotX {

    // TODO don't let people spam join/leave
    // TODO Shortcuts

    // TODO Inactivity timeout
    // TODO Check scores in game
    // TODO some sort of bug with !cah drop and causing another pick
    // TODO Hold drop's until after card is picked, should fix above bug
    // TODO Opportunity to dump hand after 10 rounds
    // TODO HOF
    // TODO Delay on remove from game on disconnect

    private final List<Future> scheduledTasks = new ArrayList<Future>();

    protected enum GameStatus {
        Idle, // No game is playing
        WaitingForPlayers, // 30 second period where players should join
        WaitingForCards, // Waiting for all players to play cards
        ChoosingWinner // Waiting for the czar to pick a winner
    }

    // \x03#,# \u0003 Colors
    // \x02 \u0002 Bold

    @Option(name = "-c", usage = "Sets the channel (e.g. \"#cah\")", required = true, handler = StringOptionHandler.class)
    protected String gameChannel;
    @Option(name = "-s", usage = "Sets the server (e.g. \"irc.esper.net\")", required = true, handler = StringOptionHandler.class)
    protected String ircNetwork;
    @Option(name = "-n", usage = "Sets the bot's nickname (e.g. \"CAHBot\")", handler = StringOptionHandler.class)
    protected String botNick = "CAHBot";
    @Option(name = "-P", usage = "Sets the password to be used for NickServ", handler = StringOptionHandler.class)
    private String nickservPassword = "";
    @Option(name = "-p", usage = "Sets the card packs to be used, space-delimited (e.g. \"FirstVersion SecondExpansion\")", handler = StringArrayOptionHandler.class)
    protected String[] cardPacks = new String[0];
    @Option(name = "-a", usage = "Raw IRC line to be sent for authentication", handler = StringOptionHandler.class)
    private String authLine = "";
    @Option(name = "-d", usage = "Sets the delay between messages sent from the bot in milliseconds", handler = LongOptionHandler.class)
    private long messageDelay = 2300L;

    public static void main(String[] args) throws Exception {
        final CardsAgainstHumanity bot = new CardsAgainstHumanity();
        bot.doMain(args);
    }

    protected ArrayList<Player> currentPlayers = new ArrayList<Player>();
    protected ArrayList<Player> allPlayers = new ArrayList<Player>();
    // private ArrayList<Player> blacklist = new ArrayList<Player>();
    private ArrayList<Player> currentShuffledPlayers;
    private ArrayList<String> originalBlackCards = new ArrayList<String>();
    private ArrayList<String> activeBlackCards = new ArrayList<String>();
    private ArrayList<String> originalWhiteCards = new ArrayList<String>();
    private ArrayList<String> activeWhiteCards = new ArrayList<String>();
    private String currentBlackCard;
    private ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
    protected GameStatus currentGameStatus = GameStatus.Idle;
    protected Player currentCzar;
    public int requiredAnswers = 1;

    private enum CardPackType {
        BLACK, WHITE
    }

    private void addCardPacks() throws Exception {
        if (cardPacks.length < 1) cardPacks = new String[]{"FirstVersion"};
        for (String name : cardPacks) {
            final File f = new File(name + ".txt");
            ifNotExists(f);
            if (!f.exists()) {
                System.out.println("Could not find \"" + f.getName() + "\". Skipping.");
                continue;
            }
            CardPackType cpt = CardPackType.BLACK;
            final BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equalsIgnoreCase("___WHITE___")) {
                    cpt = CardPackType.WHITE;
                    continue; // don't actually add this card
                } else if (line.equalsIgnoreCase("___BLACK___")) {
                    cpt = CardPackType.BLACK;
                    continue; // don't actually add this card
                }
                if (line.trim().isEmpty()) continue; // don't add empty lines
                if (cpt == CardPackType.WHITE) originalWhiteCards.add(line);
                else originalBlackCards.add(line);
            }
            br.close();
        }
    }

    private void doMain(String[] args) throws Exception {
        parseCommandLine(args);
        addCardPacks();
        this.getListenerManager().addListener(new CAHListener(this));
        this.setName(botNick);
        this.setVerbose(true);
        this.connect(ircNetwork);
        if (!nickservPassword.isEmpty()) this.identify(nickservPassword);
        if (!authLine.isEmpty()) this.sendRawLineNow(authLine);
        this.joinChannel(gameChannel);
        this.setMessageDelay(messageDelay);
    }

    private void parseCommandLine(String[] args) {
        final CmdLineParser clp = new CmdLineParser(this);
        try {
            clp.parseArgument(args);
            if (clp.getOptions().size() < 2) throw new CmdLineException(clp, "Not enough options!");
        } catch (CmdLineException e) {
            System.out.println(e.getMessage());
            e.getParser().printUsage(System.out);
            System.exit(1);
        }
    }

    public void checkForPlayedCards() {
        int playedCardsCount = 0;
        for (Player player : currentPlayers) {
            if (player.getPlayedCard() != null)
                playedCardsCount++;
        }
        if (playedCardsCount + 1 == currentPlayers.size()) {
            this.message("All players have played their white cards");
            this.message("The black card is " + Colors.BOLD + "\"" + currentBlackCard + "\"" + Colors.NORMAL + " The white cards are:");
            playedCardsCount = 0;
            currentShuffledPlayers = new ArrayList<Player>(currentPlayers);
            currentShuffledPlayers.remove(currentCzar);
            Collections.shuffle(currentShuffledPlayers);
            for (Player player : currentShuffledPlayers) {
                playedCardsCount++;
                this.message(playedCardsCount + ") " + player.getPlayedCard());
            }
            this.message(currentCzar.getName() + ": Pick the best white card");
            currentGameStatus = GameStatus.ChoosingWinner;
        }
    }

    private void cancelAllTasks() {
        final List<Future> removedTasks = new ArrayList<Future>();
        synchronized (scheduledTasks) {
            for (Future ft : scheduledTasks) {
                ft.cancel(true);
                removedTasks.add(ft);
            }
            scheduledTasks.removeAll(removedTasks);
        }
    }

    private void cancelTask(Future ft) {
        ft.cancel(true);
        synchronized (scheduledTasks) {
            if (!scheduledTasks.contains(ft)) return;
            scheduledTasks.remove(ft);
        }
    }

    private void registerTask(Future ft) {
        synchronized (scheduledTasks) {
            scheduledTasks.add(ft);
        }
    }

    protected void drop(String name) {
        Player player = getPlayer(name);
        if (player == null) {
            return;
        }
        this.message(player.getName() + " has left this game of Cards Against Humanity with " + player.getScore() + " points!");
        currentPlayers.remove(player);
        // blacklist.add(p);
        if (currentPlayers.size() < 3)
            stop();
        if (currentCzar.equals(player))
            newCzar();
        else
            checkForPlayedCards();
    }

    public String getOrdinal(int value) {
        int hundredRemainder = value % 100;
        if (hundredRemainder >= 10 && hundredRemainder <= 20) {
            return "th";
        }
        int tenRemainder = value % 10;
        switch (tenRemainder) {
            case 1:
                return "st";
            case 2:
                return "nd";
            case 3:
                return "rd";
            default:
                return "th";
        }
    }

    protected Player getPlayer(String name) {
        for (Player player : currentPlayers) {
            if (player.getName().equals(name)) // Can't compare Player and String
                return player;
        }
        return null;
    }

    private void ifNotExists(File... files) {
        for (File file : files) {
            if (file.exists()) {
                continue;
            }
            System.out.println("Saving " + file);
            InputStream inputStream = CardsAgainstHumanity.class.getClassLoader().getResourceAsStream(file.getName());
            if (inputStream == null) return;
            try {
                if (!file.createNewFile()) throw new IOException("Could not create file \"" + file.getName() + "\"");
                FileOutputStream outputStream = new FileOutputStream(file);
                byte buffer[] = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.close();
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    protected void join(String name) {
        if (currentGameStatus == GameStatus.Idle) {
            this.message("There is no game currently playing. Try starting one with !cah start");
            return;
        }
        for (Player player : currentPlayers) {
            if (player.getName().equals(name)) {
                this.message(name + ": you can't join this game twice!");
                return;
            }
        }
        /*
         * for (Player p : blacklist) { if (p.getName().equals(name)) {
         * this.message(name + ": You can't join a game after leaving one");
         * return; } }
         */
        for (Player player : allPlayers) {
            if (player.getName().equals(name)) { // can't compare Player and String
                currentPlayers.add(player);
                this.message(Colors.BOLD + name + " rejoins this game of Cards Against Humanity!");
                return;
            }
        }
        Player player = new Player(name, this);
        currentPlayers.add(player);
        allPlayers.add(player);
        this.message(Colors.BOLD + name + " joins this game of Cards Against Humanity!");
    }

    public void message(String message) {
        this.sendMessage(gameChannel, message);
    }

    protected void nag() { // Remove unused argument (String sender)
        if (currentGameStatus == GameStatus.WaitingForCards) {
            String missingPlayers = "";
            for (Player player : currentPlayers) {
                if (!player.equals(currentCzar) && player.getPlayedCard() == null) {
                    System.out.println(player.getName());
                    missingPlayers += player.getName() + " ";
                }
            }
            this.message("Waiting for " + missingPlayers + "to submit cards");
        } else if (currentGameStatus == GameStatus.ChoosingWinner) {
            this.message("Waiting for " + currentCzar.getName() + " to pick the winning card");
        }
    }

    private void newCzar() {
        this.newCzar(null);
    }

    private void newCzar(Player czar) {
        if (czar == null) {
            int index = currentPlayers.indexOf(currentCzar);
            if (index++ > currentPlayers.size() - 1) {
                index = 0;
            }
            czar = currentPlayers.get(index);
        }
        currentCzar = czar;
        this.message(currentCzar.getName() + " is the next czar.");
    }

    private void nextTurn() {
        this.nextTurn(null);
    }

    private void nextTurn(Player czar) {
        newCzar(czar);
        if (activeBlackCards.size() < 1) {
            activeBlackCards = new ArrayList<String>(originalBlackCards);
            Collections.shuffle(activeBlackCards);
        }
        currentBlackCard = "\u00030,1" + activeBlackCards.remove(0) + "\u0003";
        requiredAnswers = this.countMatches(currentBlackCard, "_");
        currentBlackCard = currentBlackCard.replace("_", "<BLANK>"); // Actually assign this value
        this.message("The next black card is " + Colors.BOLD + "\"" + currentBlackCard + "\"");
        if (requiredAnswers > 1)
            message("Be sure to play " + requiredAnswers + " white cards this round.");
        currentGameStatus = GameStatus.WaitingForCards;
        for (Player player : currentPlayers) {
            player.wipePlayedCard();
            player.drawTo10();
            if (!player.equals(currentCzar))
                player.showCardsToPlayer();
        }
    }

    private int countMatches(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index + 1)) != -1) {
            count++;
        }
        return count;
    }

    public String nextWhiteCard() {
        if (activeWhiteCards.size() < 1) {
            activeWhiteCards = new ArrayList<String>(originalWhiteCards);
            Collections.shuffle(activeWhiteCards);
        }
        return activeWhiteCards.remove(0);
    }

    protected void pickWinner(String winningNumber) {
        int cardNumber;
        try {
            cardNumber = Integer.parseInt(winningNumber);
        } catch (NumberFormatException e) {
            this.message(currentCzar.getName() + ": You have picked an invalid card; pick again.");
            return;
        }
        Player winningPlayer;
        try {
            winningPlayer = currentShuffledPlayers.get(cardNumber - 1);
        } catch (IndexOutOfBoundsException e) {
            this.message(currentCzar.getName() + ": You have picked an invalid card; pick again.");
            return;
        }
        String winningCard = winningPlayer.getPlayedCard();
        this.message("The winning card is " + winningCard + "played by " + Colors.BOLD + winningPlayer.getName() + Colors.NORMAL + ". " + Colors.BOLD + winningPlayer.getName() + Colors.NORMAL + " is awarded one point.");
        winningPlayer.addPoint();
        nextTurn();
    }

    protected void start() {
        activeBlackCards = new ArrayList<String>(originalBlackCards);
        activeWhiteCards = new ArrayList<String>(originalWhiteCards);

        Collections.shuffle(activeBlackCards);
        Collections.shuffle(activeWhiteCards);

        this.message("Game begins in 45 seconds. Type !cah join to join the game.");

        currentGameStatus = GameStatus.WaitingForPlayers;

        registerTask(stpe.schedule(new Runnable() {
            public void run() {
                message("Game starts in 30 seconds.");
            }
        }, 15L, TimeUnit.SECONDS));
        registerTask(stpe.schedule(new Runnable() {
            public void run() {
                message("Game starts in 15 seconds.");
            }
        }, 30L, TimeUnit.SECONDS));
        registerTask(stpe.schedule(new Runnable() {
            public void run() {
                if (currentPlayers.size() < 3) {
                    message("Not enough players to start a game; three are required.");
                    currentPlayers.clear();
                    currentGameStatus = GameStatus.Idle;
                    return;
                }
                // Everything here is pre game stuff
                message("Game starting now!");
                CardsAgainstHumanity.this.nextTurn(currentPlayers.get(0));
            }
        }, 45L, TimeUnit.SECONDS)); // 45 seconds
    }

    protected String getScoresString() {
        final StringBuilder sb = new StringBuilder();
        for (Player p : allPlayers) sb.append("[").append(p.getName()).append(" ").append(p.getScore()).append("] ");
        return sb.substring(0, sb.length() - 1);
    }

    protected void stop() {
        cancelAllTasks();
        currentGameStatus = GameStatus.Idle;
        currentCzar = null;
        this.message("The game is over!");
        String scoresMessage = "Scores for this game were: ";
        int winningScore = 0;
        for (Player player : allPlayers) {
            scoresMessage += "[" + player.getName() + " " + player.getScore() + "] ";
            if (player.getScore() > winningScore)
                winningScore = player.getScore();
        }
        this.message(scoresMessage);
        scoresMessage = "Winners this game were: ";
        for (Player player : allPlayers) {
            if (player.getScore() == winningScore)
                scoresMessage += player.getName() + " ";
        }
        this.message(scoresMessage);
        allPlayers.clear();
        currentPlayers.clear();
    }
}
