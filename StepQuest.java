import java.util.*;

/**
 * StepQuest: a console step counter where walking earns energy,
 * and energy is spent on minigames that pay out coins.
 * Unique mechanic: hit your daily goal to build a streak multiplier.
 *
 * Compile: javac StepQuest.java
 * Run:     java StepQuest
 */
public class StepQuest {

    // ---------- Step input (swap this for a real sensor later) ----------
    interface StepSource {
        int readSteps();
    }

    static class ManualStepSource implements StepSource {
        private final Scanner in;
        ManualStepSource(Scanner in) { this.in = in; }
        public int readSteps() {
            System.out.print("How many steps did you walk? ");
            try { return Math.max(0, Integer.parseInt(in.nextLine().trim())); }
            catch (NumberFormatException e) { return 0; }
        }
    }

    static class RandomWalkSource implements StepSource {
        private final Random rng = new Random();
        public int readSteps() { return 200 + rng.nextInt(1800); }
    }

    // ---------- Step counter ----------
    static class StepCounter {
        static final int STEPS_PER_ENERGY = 500;
        private final int dailyGoal;
        private int stepsToday = 0;
        private int unconverted = 0;
        private boolean goalRewarded = false;

        StepCounter(int dailyGoal) { this.dailyGoal = dailyGoal; }

        static final int MAX_STEPS_PER_ENTRY = 3000;  // blocks absurd manual entries
        static final int MAX_STEPS_PER_DAY = 25000;   // blocks farming past a plausible day

        /** Adds steps after plausibility checks; returns how many were actually accepted. */
        int add(int steps) {
            int accepted = Math.min(steps, MAX_STEPS_PER_ENTRY);
            accepted = Math.min(accepted, MAX_STEPS_PER_DAY - stepsToday);
            accepted = Math.max(accepted, 0);
            stepsToday += accepted;
            unconverted += accepted;
            return accepted;
        }

        /** Turns accumulated steps into whole energy points. */
        int convertToEnergy() {
            int energy = unconverted / STEPS_PER_ENERGY;
            unconverted %= STEPS_PER_ENERGY;
            return energy;
        }

        /** True exactly once, the moment the goal is first reached. */
        boolean checkGoalReached() {
            if (!goalRewarded && stepsToday >= dailyGoal) {
                goalRewarded = true;
                return true;
            }
            return false;
        }

        int getStepsToday() { return stepsToday; }
        int getDailyGoal() { return dailyGoal; }
        int getUnconverted() { return unconverted; }
    }

    // ---------- Player ----------
    static class Player {
        private final String name;
        private int energy = 0;
        private int coins = 0;
        private int streak = 0;
        private final List<Voucher> wallet = new ArrayList<>();

        Player(String name) { this.name = name; }

        void addEnergy(int e) { energy += e; }

        boolean spendEnergy(int cost) {
            if (energy < cost) return false;
            energy -= cost;
            return true;
        }

        /** Adds coins with the streak multiplier applied; returns the amount earned. */
        int addCoins(int base) {
            int earned = (int) Math.round(base * getMultiplierExact());
            coins += earned;
            return earned;
        }

        boolean spendCoins(int cost) {
            if (coins < cost) return false;
            coins -= cost;
            return true;
        }

        void addVoucher(Voucher v) { wallet.add(v); }
        List<Voucher> getWallet() { return wallet; }

        void incrementStreak() { streak++; }

        /** Streak bonus: +25% per streak day, capped at x2. */
        double getMultiplierExact() { return Math.min(2.0, 1.0 + 0.25 * streak); }

        String getName() { return name; }
        int getEnergy() { return energy; }
        int getCoins() { return coins; }
        int getStreak() { return streak; }
    }

    // ---------- Minigames ----------
    static abstract class MiniGame {
        private final String name;
        private final int energyCost;

        MiniGame(String name, int energyCost) {
            this.name = name;
            this.energyCost = energyCost;
        }

        /** Returns base coins won (before streak multiplier). */
        abstract int play(Scanner in);

        String getName() { return name; }
        int getEnergyCost() { return energyCost; }
    }

    static class LuckyDice extends MiniGame {
        private final Random rng = new Random();
        LuckyDice() { super("Lucky Dice", 1); }

        int play(Scanner in) {
            System.out.print("Guess the dice roll (1-6): ");
            int guess = parse(in.nextLine());
            int roll = 1 + rng.nextInt(6);
            System.out.println("Rolled a " + roll + ".");
            if (guess == roll) { System.out.println("Exact! +30 coins"); return 30; }
            if (Math.abs(guess - roll) == 1) { System.out.println("So close! +5 coins"); return 5; }
            System.out.println("No luck this time.");
            return 0;
        }
    }

    static class NumberGuess extends MiniGame {
        private final Random rng = new Random();
        NumberGuess() { super("Number Guess", 2); }

        int play(Scanner in) {
            int target = 1 + rng.nextInt(50);
            System.out.println("I'm thinking of a number from 1 to 50. You have 6 tries.");
            for (int tries = 1; tries <= 6; tries++) {
                System.out.print("Try " + tries + ": ");
                int g = parse(in.nextLine());
                if (g == target) {
                    int reward = 10 + (6 - tries) * 8;
                    System.out.println("Correct! +" + reward + " coins");
                    return reward;
                }
                System.out.println(g < target ? "Higher." : "Lower.");
            }
            System.out.println("Out of tries. It was " + target + ".");
            return 0;
        }
    }

    static class MemoryDigits extends MiniGame {
        private final Random rng = new Random();
        MemoryDigits() { super("Memory Digits", 3); }

        int play(Scanner in) {
            StringBuilder seq = new StringBuilder();
            for (int i = 0; i < 6; i++) seq.append(rng.nextInt(10));
            System.out.println("Memorize: " + seq);
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            for (int i = 0; i < 30; i++) System.out.println();
            System.out.print("Type the digits: ");
            String answer = in.nextLine().trim();
            if (answer.equals(seq.toString())) {
                System.out.println("Perfect memory! +60 coins");
                return 60;
            }
            System.out.println("It was " + seq + ".");
            return 0;
        }
    }

    // ---------- Rewards ----------
    static abstract class Voucher {
        private final String partner;
        private final int cost;
        private final String code;

        Voucher(String partner, int cost) {
            this.partner = partner;
            this.cost = cost;
            this.code = partner.substring(0, Math.min(3, partner.length())).toUpperCase()
                    + "-" + (1000 + new Random().nextInt(9000));
        }

        abstract String describe();

        String getPartner() { return partner; }
        int getCost() { return cost; }
        String getCode() { return code; }
    }

    static class PercentOffVoucher extends Voucher {
        private final int percent;
        PercentOffVoucher(String partner, int percent, int cost) {
            super(partner, cost);
            this.percent = percent;
        }
        String describe() { return percent + "% off at " + getPartner(); }
    }

    static class FreeItemVoucher extends Voucher {
        private final String item;
        FreeItemVoucher(String partner, String item, int cost) {
            super(partner, cost);
            this.item = item;
        }
        String describe() { return "Free " + item + " at " + getPartner(); }
    }

    /** Sells vouchers for coins. Vouchers are created fresh on each purchase. */
    static class RewardShop {
        private final List<java.util.function.Supplier<Voucher>> catalog = List.of(
                () -> new PercentOffVoucher("Campus Cafe", 10, 150),
                () -> new FreeItemVoucher("Campus Cafe", "coffee", 300),
                () -> new PercentOffVoucher("Student Bookstore", 15, 500)
        );

        void show() {
            System.out.println("\n--- Reward Shop ---");
            for (int i = 0; i < catalog.size(); i++) {
                Voucher v = catalog.get(i).get();
                System.out.println((i + 1) + ") " + v.describe() + " - " + v.getCost() + " coins");
            }
        }

        int size() { return catalog.size(); }

        /** Returns the purchased voucher, or null if the player can't afford it. */
        Voucher buy(int index, Player p) {
            Voucher v = catalog.get(index).get();
            if (!p.spendCoins(v.getCost())) return null;
            p.addVoucher(v);
            return v;
        }
    }

    // ---------- Helpers ----------
    static int parse(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    static void showStats(Player p, StepCounter c) {
        System.out.println("\n--- " + p.getName() + " ---");
        System.out.printf("Steps today: %d / %d%n", c.getStepsToday(), c.getDailyGoal());
        System.out.printf("Steps until next energy: %d%n",
                StepCounter.STEPS_PER_ENERGY - c.getUnconverted());
        System.out.printf("Energy: %d | Coins: %d | Streak: %d (x%.2f)%n",
                p.getEnergy(), p.getCoins(), p.getStreak(), p.getMultiplierExact());
    }

    static void logSteps(int steps, Player p, StepCounter c) {
        if (steps <= 0) { System.out.println("No steps logged."); return; }
        int accepted = c.add(steps);
        if (accepted < steps) {
            System.out.println("Only " + accepted + " of those steps were counted (daily and per-entry limits).");
        }
        steps = accepted;
        int energy = c.convertToEnergy();
        p.addEnergy(energy);
        System.out.println("+" + steps + " steps, +" + energy + " energy.");
        if (c.checkGoalReached()) {
            p.incrementStreak();
            System.out.printf("Daily goal reached! Streak is now %d (coin multiplier x%.2f).%n",
                    p.getStreak(), p.getMultiplierExact());
        }
    }

    static void playMenu(List<MiniGame> games, Player p, Scanner in) {
        System.out.println("\nEnergy available: " + p.getEnergy());
        for (int i = 0; i < games.size(); i++) {
            MiniGame g = games.get(i);
            System.out.println((i + 1) + ") " + g.getName() + " (cost " + g.getEnergyCost() + " energy)");
        }
        System.out.print("Choose a game (or 0 to go back): ");
        int choice = parse(in.nextLine());
        if (choice < 1 || choice > games.size()) return;
        MiniGame game = games.get(choice - 1);
        if (!p.spendEnergy(game.getEnergyCost())) {
            System.out.println("Not enough energy. Go for a walk!");
            return;
        }
        int base = game.play(in);
        if (base > 0) {
            int earned = p.addCoins(base);
            System.out.println("Streak bonus applied: you earned " + earned + " coins total.");
        }
    }

    static void shopMenu(RewardShop shop, Player p, Scanner in) {
        shop.show();
        System.out.println("Your coins: " + p.getCoins());
        System.out.print("Buy which voucher (0 to go back)? ");
        int choice = parse(in.nextLine());
        if (choice < 1 || choice > shop.size()) return;
        Voucher v = shop.buy(choice - 1, p);
        if (v == null) System.out.println("Not enough coins yet. Keep walking!");
        else System.out.println("Redeemed! " + v.describe() + " | Code: " + v.getCode());
    }

    static void showWallet(Player p) {
        if (p.getWallet().isEmpty()) { System.out.println("No vouchers yet."); return; }
        for (Voucher v : p.getWallet()) {
            System.out.println(v.describe() + " | Code: " + v.getCode());
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.print("Your name: ");
        Player player = new Player(in.nextLine().trim());
        StepCounter counter = new StepCounter(5000);

        RewardShop shop = new RewardShop();
        StepSource manual = new ManualStepSource(in);
        StepSource random = new RandomWalkSource();
        List<MiniGame> games = List.of(new LuckyDice(), new NumberGuess(), new MemoryDigits());

        boolean running = true;
        while (running) {
            System.out.println("\n1) Log steps  2) Simulate a walk  3) Play minigame  4) Stats  5) Reward shop  6) My vouchers  7) Quit");
            System.out.print("> ");
            switch (in.nextLine().trim()) {
                case "1" -> logSteps(manual.readSteps(), player, counter);
                case "2" -> {
                    int steps = random.readSteps();
                    System.out.println("You wandered around and walked " + steps + " steps.");
                    logSteps(steps, player, counter);
                }
                case "3" -> playMenu(games, player, in);
                case "4" -> showStats(player, counter);
                case "5" -> shopMenu(shop, player, in);
                case "6" -> showWallet(player);
                case "7" -> running = false;
                default -> System.out.println("Pick 1-7.");
            }
        }
        System.out.println("Thanks for walking, " + player.getName() + "!");
    }
}
