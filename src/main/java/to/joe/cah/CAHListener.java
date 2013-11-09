package to.joe.cah;

import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NickChangeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CAHListener extends ListenerAdapter {

    private final CardsAgainstHumanity cah;

    protected CAHListener(CardsAgainstHumanity cah) {
        this.cah = cah;
    }

    public void onKick(KickEvent e) {
        cah.drop(e.getRecipient().getNick());
    }

    public void onPart(PartEvent e) {
        cah.drop(e.getUser().getNick());
    }

    public void onQuit(QuitEvent e) {
        final String sourceNick = e.getUser().getNick();
        if (!sourceNick.equals(cah.getNick()))
            cah.drop(sourceNick);
        else {
            try {
                cah.connect(cah.ircNetwork);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            cah.joinChannel(cah.gameChannel);
        }
    }

    public void onMessage(MessageEvent e) {
        final String message = e.getMessage();
        final String channel = e.getChannel().getName();
        final String sender = e.getUser().getNick();
        Pattern pattern3 = Pattern.compile("!cah boot ([a-zA-Z0-9]+) *", Pattern.CASE_INSENSITIVE);
        Matcher matcher3 = pattern3.matcher(message);
        if (!channel.equalsIgnoreCase(cah.gameChannel)) return;
        if (message.equalsIgnoreCase("!cah join")) // fix faulty if
            cah.join(sender);
        else if (message.equalsIgnoreCase("!cah drop"))
            cah.drop(sender);
        else if (message.equalsIgnoreCase("!cah start") && cah.currentGameStatus == CardsAgainstHumanity.GameStatus.Idle)
            cah.start();
        else if (message.equalsIgnoreCase("!cah stop") && cah.currentGameStatus != CardsAgainstHumanity.GameStatus.Idle)
            cah.stop();
        else if (message.equalsIgnoreCase("turn"))
            cah.nag();
        else if (message.equalsIgnoreCase("check"))
            cah.checkForPlayedCards();
        else if (matcher3.matches()) cah.drop(matcher3.group(1));
    }

    public void onPrivateMessage(PrivateMessageEvent e) {
        final String message = e.getMessage();
        final String sender = e.getUser().getNick();
        Pattern pattern1 = Pattern.compile("play ((?:[0-9]+ ?){" + cah.requiredAnswers + "}) *", Pattern.CASE_INSENSITIVE);
        Matcher matcher1 = pattern1.matcher(message);

        Pattern pattern2 = Pattern.compile("pick ([0-9]+) *", Pattern.CASE_INSENSITIVE);
        Matcher matcher2 = pattern2.matcher(message);
        if (message.equalsIgnoreCase("cards")) cah.getPlayer(sender).showCardsToPlayer();
        else if (matcher1.matches() && cah.currentGameStatus == CardsAgainstHumanity.GameStatus.WaitingForCards && !cah.currentCzar.getName().equals(sender))
            cah.getPlayer(sender).playCard(matcher1.group(1));
        else if (matcher2.matches() && cah.currentGameStatus == CardsAgainstHumanity.GameStatus.ChoosingWinner && cah.currentCzar.getName().equals(sender))
            cah.pickWinner(matcher2.group(1));
    }

    public void onNickChange(NickChangeEvent e) {
        final String oldNick = e.getOldNick();
        final String newNick = e.getNewNick();
        for (Player player : cah.allPlayers) {
            if (player.getName().equals(oldNick)) {
                player.setName(newNick);
                return;
            }
        }
        for (Player player : cah.currentPlayers) {
            if (player.getName().equals(oldNick)) {
                player.setName(newNick);
                return;
            }
        }
    }
}
