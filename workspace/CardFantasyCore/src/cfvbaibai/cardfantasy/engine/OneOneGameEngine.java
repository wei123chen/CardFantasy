package cfvbaibai.cardfantasy.engine;

import java.util.Collection;
import java.util.List;

import cfvbaibai.cardfantasy.CardFantasyRuntimeException;
import cfvbaibai.cardfantasy.GameOverSignal;
import cfvbaibai.cardfantasy.data.PlayerInfo;

public class OneOneGameEngine {

    private Board board;
    private Rule rule;
    private GameUI ui;
    private int activePlayerNumber;

    private int getActivePlayerNumber() {
        return activePlayerNumber;
    }

    private void setActivePlayerNumber(int activePlayerNumber) {
        this.activePlayerNumber = activePlayerNumber;
    }

    private Player getActivePlayer() {
        return this.board.getPlayer(this.getActivePlayerNumber());
    }

    private Player getInactivePlayer() {
        assert (this.board.getPlayerCount() == 2);
        return this.board.getPlayer(1 - this.getActivePlayerNumber());
    }

    private int round;

    public int getRound() {
        return this.round;
    }

    public OneOneGameEngine(GameUI ui, Rule rule) {
        board = new Board();
        this.rule = rule;
        this.ui = ui;
    }

    public void RegisterPlayers(PlayerInfo player1Info, PlayerInfo player2Info) {
        Player player1 = new Player(player1Info);
        board.addPlayer(player1);
        ui.playerAdded(player1);
        Player player2 = new Player(player2Info);
        board.addPlayer(player2);
        ui.playerAdded(player2);
    }

    public GameResult playGame() {
        this.ui.gameStarted(board, rule);
        this.setActivePlayerNumber(0);
        this.round = 0;
        GameResult result = proceedGame();
        this.ui.gameEnded(result);
        return result;
    }

    private GameResult proceedGame() {
        Phase phase = Phase.Start;
        Phase nextPhase = Phase.Unknown;
        try {
            while (true) {
                if (phase == Phase.Start) {
                    nextPhase = roundStart();
                } else if (phase == Phase.Draw) {
                    nextPhase = drawCard();
                } else if (phase == Phase.Standby) {
                    nextPhase = standby();
                } else if (phase == Phase.Summon) {
                    nextPhase = summonCards();
                } else if (phase == Phase.Battle) {
                    nextPhase = battle();
                } else if (phase == Phase.End) {
                    nextPhase = roundEnd();
                } else {
                    throw new CardFantasyRuntimeException(String.format("Unknown phase encountered: %s", phase));
                }
                ui.phaseChanged(getActivePlayer(), phase, nextPhase);
                phase = nextPhase;
                nextPhase = Phase.Unknown;
            }
        } catch (GameOverSignal signal) {
            return new GameResult(this.board, this.board.getPlayer(0), this.round, GameEndCause.TOO_LONG);
        } catch (HeroDieSignal signal) {
            return new GameResult(this.board, getOpponent(signal.getDeadPlayer()), this.round, GameEndCause.HERO_DIE);
        } catch (AllCardsDieSignal signal) {
            return new GameResult(this.board, getOpponent(signal.getDeadPlayer()), this.round, GameEndCause.ALL_CARDS_DIE);
        }
    }

    private Player getOpponent(Player player) {
        return this.getActivePlayer() == player ? this.getInactivePlayer() : this.getActivePlayer();
    }

    private Phase summonCards() {
        List<CardInfo> summonedCards = this.ui.summonCards(this.getActivePlayer(), round);
        Hand hand = this.getActivePlayer().getHand();
        Field field = this.getActivePlayer().getField();
        for (CardInfo summonedCard : summonedCards) {
            hand.removeCard(summonedCard);
            field.addCard(summonedCard);
        }
        return Phase.Battle;
    }

    private Phase standby() {
        return Phase.Summon;
    }

    private Phase roundEnd() {
        Collection<CardInfo> allHandCards = this.board.getAllHandCards();
        for (CardInfo card : allHandCards) {
            int summonDelay = card.getSummonDelay();
            if (summonDelay > 0) {
                card.setSummonDelay(summonDelay - 1);
            }
        }

        Player previousPlayer = getActivePlayer();
        this.ui.roundEnded(previousPlayer, this.round);
        ++this.round;
        int nextPlayerNumber = (this.getActivePlayerNumber() + 1) % board.getPlayerCount();
        this.setActivePlayerNumber(nextPlayerNumber);
        Player nextPlayer = this.getActivePlayer();
        ui.playerChanged(previousPlayer, nextPlayer);
        return Phase.Start;
    }

    private Phase battle() throws HeroDieSignal {
        /***
         * Algorithm: For each card in field of active user: Check whether
         * target player has a card in field in the same position - Yes: Attack.
         * Card died? - Yes: Move card to grave. Leave an empty position. - No:
         * Go on. - No: Attack Hero. Trigger hero HP check. Remove all empty
         * position in fields.
         */

        Field myField = getActivePlayer().getField();
        Field opField = getInactivePlayer().getField();
        for (int i = 0; i < myField.size(); ++i) {
            CardInfo myCard = myField.getCard(i);
            CardInfo opCard = opField.getCard(i);
            if (myCard == null) {
                continue;
            }
            if (opCard == null) {
                attackHero(myCard, getInactivePlayer());
            } else {
                attackCard(myCard, opCard);
            }
        }

        myField.compact();
        opField.compact();

        return Phase.End;
    }

    private void attackCard(CardInfo myCard, CardInfo opCard) {
        opCard.setHP(opCard.getHP() - myCard.getAT());
        this.ui.attackCard(myCard, opCard, myCard.getAT());
        if (opCard.getHP() <= 0) {
            cardDead(opCard);
        }
    }

    private void cardDead(CardInfo deadCard) {
        Player owner = deadCard.getOwner();
        Field field = owner.getField();
        // Set field position to null
        for (int i = 0; i < field.size(); ++i) {
            CardInfo card = field.getCard(i);
            if (deadCard == card) {
                field.expelCard(i);
                owner.getGrave().addCard(card);
                break;
            }
        }
        this.ui.cardDead(deadCard);
    }

    private void attackHero(CardInfo myCard, Player targetPlayer) throws HeroDieSignal {
        try {
            targetPlayer.setLife(targetPlayer.getLife() - myCard.getAT());
        } finally {
            this.ui.attackHero(myCard, targetPlayer, myCard.getAT());
        }
    }

    private Phase roundStart() throws GameOverSignal, AllCardsDieSignal {
        if (this.round > rule.getMaxRound()) {
            throw new GameOverSignal();
        }
        if (this.getActivePlayer().getDeck().size() == 0 &&
            this.getActivePlayer().getField().size() == 0 &&
            this.getActivePlayer().getHand().size() == 0) {
            throw new AllCardsDieSignal(this.getActivePlayer());
        }

        this.ui.roundStarted(this.getActivePlayer(), this.round);
        return Phase.Draw;
    }

    private Phase drawCard() {
        Player activePlayer = this.getActivePlayer();
        Hand hand = activePlayer.getHand();
        if (hand.size() >= this.rule.getMaxHandCards()) {
            ui.cantDrawHandFull(activePlayer);
            return Phase.Standby;
        }
        Deck deck = activePlayer.getDeck();
        if (deck.isEmpty()) {
            ui.cantDrawDeckEmpty(activePlayer);
            return Phase.Standby;
        }
        CardInfo newCard = deck.draw();
        hand.addCard(newCard);
        ui.cardDrawed(activePlayer, newCard);
        return Phase.Standby;
    }
}