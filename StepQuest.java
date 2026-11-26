import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * StepQuest: a console step counter that motivates students to walk and compete.
 * Walking earns energy -> energy plays minigames -> coins buy discount vouchers.
 *
 * Motivation features:
 *  - Daily goal streaks (+25% coins per day, max x2) that RESET if you miss a day
 *  - SHARED weekly leaderboard: every student's steps go into one file, and the
 *    top 3 at the end of the week win bonus coins (500 / 300 / 150)
 *  - Campus checkpoints with check-in bonuses
 *  - Auto save/load (one save file per student)
 *
 * To compete: put this folder somewhere all students can reach (shared drive,
 * lab computer, ...) and have each student run the game with a UNIQUE name.
 *
 * Run: java StepQuest.java     (or: javac StepQuest.java && java StepQuest)
 */
public class StepQuest {

    // ======================= Clock (lets us demo "next day / next week") =======================
    static class GameClock {
        private LocalDate today = LocalDate.now();
        LocalDate today() { return today; }
        void advanceDays(int days) { today = today.plusDays(days); }
    }

    // ======================= Step input (swap for a real sensor later) =======================
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

    // ======================= Step counter =======================
    static class StepCounter {
        static final int STEPS_PER_ENERGY = 500;
        static final int MAX_STEPS_PER_ENTRY = 3000;  // blocks absurd manual entries
        static final int MAX_STEPS_PER_DAY = 25000;   // blocks farming past a plausible day

        private final int dailyGoal;
        private int stepsToday = 0;
        private int unconverted = 0;
        private boolean goalRewarded = false;

        StepCounter(int dailyGoal) { this.dailyGoal = dailyGoal; }

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

        /** True exactly once per day, the moment the goal is first reached. */
        boolean checkGoalReached() {
            if (!goalRewarded && stepsToday >= dailyGoal) {
                goalRewarded = true;
                return true;
            }
            return false;
        }

        void startNewDay() {
            stepsToday = 0;
            goalRewarded = false;
        }

        void writeTo(Properties props) {
            props.setProperty("stepsToday", String.valueOf(stepsToday));
            props.setProperty("unconverted", String.valueOf(unconverted));
            props.setProperty("goalRewarded", String.valueOf(goalRewarded));
        }

        void readFrom(Properties props) {
            int steps = Integer.parseInt(props.getProperty("stepsToday", "0"));
            int rest = Integer.parseInt(props.getProperty("unconverted", "0"));
            boolean rewarded = Boolean.parseBoolean(props.getProperty("goalRewarded", "false"));
            stepsToday = steps;
            unconverted = rest;
            goalRewarded = rewarded;
        }

        int getStepsToday() { return stepsToday; }
        int getDailyGoal() { return dailyGoal; }
        int getUnconverted() { return unconverted; }
        boolean isGoalRewarded() { return goalRewarded; }
    }

    // ======================= Vouchers =======================
    static abstract class Voucher {
        private final String partner;
        private final int cost;
        private final String code;

        Voucher(String partner, int cost) { this(partner, cost, makeCode(partner)); }

        Voucher(String partner, int cost, String code) {
            this.partner = partner;
            this.cost = cost;
            this.code = code;
        }

        private static String makeCode(String partner) {
            return partner.substring(0, Math.min(3, partner.length())).toUpperCase()
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

    /** A voucher restored from the save file. */
    static class SavedVoucher extends Voucher {
        private final String description;
        SavedVoucher(String partner, int cost, String code, String description) {
            super(partner, cost, code);
            this.description = description;
        }
        String describe() { return description; }
    }

    /** Sells vouchers for coins. Vouchers are created fresh on each purchase. */
    static class RewardShop {
        private final List<Supplier<Voucher>> catalog = List.of(
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

    // ======================= Player =======================
    static class Player {
        /** What changed when the player was brought up to date with a new day. */
        static class SyncResult {
            final boolean streakBroken;
            final String finishedWeek;  // the week key that just ended for this player, or null
            SyncResult(boolean streakBroken, String finishedWeek) {
                this.streakBroken = streakBroken;
                this.finishedWeek = finishedWeek;
            }
        }

        private final String name;
        private int energy = 0;
        private int coins = 0;
        private int streak = 0;
        private LocalDate lastGoalDate = null;  // last day the daily goal was reached
        private LocalDate stateDate = null;     // the day this state was last synced to
        private String weekKey = "";
        private int weekSteps = 0;
        private final Set<String> visitedToday = new HashSet<>();
        private final List<Voucher> wallet = new ArrayList<>();

        Player(String name) { this.name = name; }

        static String weekKey(LocalDate d) {
            return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }

        /**
         * Brings the player up to date with the given day: starts a fresh day, resets the
         * weekly total on a new week, and breaks the streak if a day was missed.
         */
        SyncResult syncDate(LocalDate today, StepCounter counter) {
            if (today.equals(stateDate)) return new SyncResult(false, null);
            counter.startNewDay();
            visitedToday.clear();

            String wk = weekKey(today);
            String finishedWeek = null;
            if (!wk.equals(weekKey)) {
                if (!weekKey.isEmpty()) finishedWeek = weekKey;
                weekKey = wk;
                weekSteps = 0;
            }

            boolean broken = false;
            if (streak > 0 && (lastGoalDate == null || lastGoalDate.isBefore(today.minusDays(1)))) {
                streak = 0;
                broken = true;
            }
            stateDate = today;
            return new SyncResult(broken, finishedWeek);
        }

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

        /** Adds coins exactly as given (used for leaderboard prizes). */
        void awardCoins(int amount) { coins += amount; }

        boolean spendCoins(int cost) {
            if (coins < cost) return false;
            coins -= cost;
            return true;
        }

        void addVoucher(Voucher v) { wallet.add(v); }

        void addWeekSteps(int steps) { weekSteps += steps; }

        void recordGoalReached(LocalDate today) {
            streak++;
            lastGoalDate = today;
        }

        /** True if this save was synced to a later date than today (only happens after the demo skip). */
        boolean isDatedAfter(LocalDate today) { return stateDate != null && stateDate.isAfter(today); }

        boolean hasVisited(String checkpoint) { return visitedToday.contains(checkpoint); }
        void markVisited(String checkpoint) { visitedToday.add(checkpoint); }

        /** Streak bonus: +25% per streak day, capped at x2. */
        double getMultiplierExact() { return Math.min(2.0, 1.0 + 0.25 * streak); }

        /** True if you have a streak to lose and haven't hit today's goal yet. */
        boolean isStreakAtRisk(StepCounter c) { return streak > 0 && !c.isGoalRewarded(); }

        String getName() { return name; }
        int getEnergy() { return energy; }
        int getCoins() { return coins; }
        int getStreak() { return streak; }
        int getWeekSteps() { return weekSteps; }
        int getVisitedCount() { return visitedToday.size(); }
        List<Voucher> getWallet() { return wallet; }

        // ---- persistence ----
        void writeTo(Properties props) {
            props.setProperty("name", name);
            props.setProperty("energy", String.valueOf(energy));
            props.setProperty("coins", String.valueOf(coins));
            props.setProperty("streak", String.valueOf(streak));
            props.setProperty("lastGoalDate", lastGoalDate == null ? "" : lastGoalDate.toString());
            props.setProperty("stateDate", stateDate == null ? "" : stateDate.toString());
            props.setProperty("weekKey", weekKey);
            props.setProperty("weekSteps", String.valueOf(weekSteps));
            props.setProperty("visitedToday", String.join(",", visitedToday));
            props.setProperty("voucherCount", String.valueOf(wallet.size()));
            for (int i = 0; i < wallet.size(); i++) {
                Voucher v = wallet.get(i);
                props.setProperty("voucher." + i,
                        v.getPartner() + "|" + v.getCost() + "|" + v.getCode() + "|" + v.describe());
            }
        }

        static Player readFrom(Properties props) {
            Player p = new Player(props.getProperty("name", "Student"));
            p.energy = Integer.parseInt(props.getProperty("energy", "0"));
            p.coins = Integer.parseInt(props.getProperty("coins", "0"));
            p.streak = Integer.parseInt(props.getProperty("streak", "0"));
            p.lastGoalDate = parseDate(props.getProperty("lastGoalDate", ""));
            p.stateDate = parseDate(props.getProperty("stateDate", ""));
            p.weekKey = props.getProperty("weekKey", "");
            p.weekSteps = Integer.parseInt(props.getProperty("weekSteps", "0"));
            String visited = props.getProperty("visitedToday", "");
            if (!visited.isEmpty()) p.visitedToday.addAll(Arrays.asList(visited.split(",")));
            int count = Integer.parseInt(props.getProperty("voucherCount", "0"));
            for (int i = 0; i < count; i++) {
                String[] parts = props.getProperty("voucher." + i, "").split("\\|", 4);
                if (parts.length == 4) {
                    p.wallet.add(new SavedVoucher(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3]));
                }
            }
            return p;
        }

        private static LocalDate parseDate(String s) {
            return (s == null || s.isEmpty()) ? null : LocalDate.parse(s);
        }
    }

    // ======================= Save / load (one file per student) =======================
    static class SaveManager {
        private final Path file;

        SaveManager(String studentName) {
            String safe = studentName.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            this.file = Paths.get("stepquest_save_" + safe + ".properties");
        }

        void save(Player p, StepCounter c) {
            Properties props = new Properties();
            p.writeTo(props);
            c.writeTo(props);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "StepQuest save");
            } catch (IOException e) {
                System.out.println("Could not save progress: " + e.getMessage());
            }
        }

        /** Returns the saved player (and restores the counter), or null if there is no usable save. */
        Player load(StepCounter c) {
            if (!Files.exists(file)) return null;
            try (InputStream in = Files.newInputStream(file)) {
                Properties props = new Properties();
                props.load(in);
                Player p = Player.readFrom(props);
                c.readFrom(props);
                return p;
            } catch (IOException | RuntimeException e) {
                System.out.println("Save file could not be read, starting fresh.");
                return null;
            }
        }
    }

    // ======================= Shared leaderboard =======================
    /**
     * Weekly leaderboard shared by every student through one file. Each student writes only
     * their own weekly total, and a file lock keeps simultaneous players from clobbering
     * each other. At the end of a week the top finishers are paid prize coins.
     * (A production version would replace the file with a server database.)
     */
    static class Leaderboard {
        static final int[] PRIZES = {500, 300, 150};   // 1st, 2nd, 3rd place
        static final int MIN_STEPS_FOR_PRIZE = 10000;  // must really walk to qualify
        static final int MIN_ENTRANTS = 2;             // no prizes for a one-person contest

        static class Entry {
            final String name;
            final int steps;
            Entry(String name, int steps) {
                this.name = name;
                this.steps = steps;
            }
        }

        private final Path file;

        Leaderboard(String fileName) { this.file = Paths.get(fileName); }

        private static String key(String weekKey, String name) {
            return "steps." + weekKey + "." + name;
        }

        /** Reads the shared file under a shared lock. */
        private Properties read() {
            Properties props = new Properties();
            if (!Files.exists(file)) return props;
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
                 FileLock lock = ch.lock(0L, Long.MAX_VALUE, true)) {
                props.load(Channels.newInputStream(ch));
            } catch (IOException e) {
                System.out.println("Leaderboard unavailable: " + e.getMessage());
            }
            return props;
        }

        /** Read-modify-write of the shared file under an exclusive lock. */
        private void update(Consumer<Properties> edit) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = ch.lock()) {
                Properties props = new Properties();
                if (ch.size() > 0) props.load(Channels.newInputStream(ch));
                edit.accept(props);
                ch.truncate(0);
                ch.position(0);
                props.store(Channels.newOutputStream(ch), "StepQuest shared leaderboard");
            } catch (IOException e) {
                System.out.println("Could not update the leaderboard: " + e.getMessage());
            }
        }

        /** Publishes this student's weekly total and drops weeks older than last week. */
        void report(Player me, LocalDate today) {
            String wk = Player.weekKey(today);
            String prev = Player.weekKey(today.minusWeeks(1));
            update(props -> {
                String mine = key(wk, me.getName());
                int existing = 0;
                try { existing = Integer.parseInt(props.getProperty(mine, "0").trim()); }
                catch (NumberFormatException ignored) { }
                props.setProperty(mine, String.valueOf(Math.max(existing, me.getWeekSteps())));
                for (String k : new ArrayList<>(props.stringPropertyNames())) {
                    if (!k.startsWith(key(wk, "")) && !k.startsWith(key(prev, ""))) props.remove(k);
                }
            });
        }

        /** Everyone's total for a week, best first (ties broken alphabetically). */
        List<Entry> standings(String weekKey) {
            Properties props = read();
            String prefix = key(weekKey, "");
            List<Entry> rows = new ArrayList<>();
            for (String k : props.stringPropertyNames()) {
                if (!k.startsWith(prefix)) continue;
                try {
                    rows.add(new Entry(k.substring(prefix.length()), Integer.parseInt(props.getProperty(k).trim())));
                } catch (NumberFormatException ignored) { }
            }
            rows.sort((a, b) -> {
                int bySteps = Integer.compare(b.steps, a.steps);
                return bySteps != 0 ? bySteps : a.name.compareToIgnoreCase(b.name);
            });
            return rows;
        }

        /** One-line summary of where the student stands this week. */
        String statusLine(Player me, LocalDate today) {
            List<Entry> rows = standings(Player.weekKey(today));
            for (int i = 0; i < rows.size(); i++) {
                if (!rows.get(i).name.equals(me.getName())) continue;
                String s = "Leaderboard: you're #" + (i + 1) + " of " + rows.size();
                if (i == 0 && rows.size() > 1) {
                    s += String.format(", leading by %,d steps.", rows.get(0).steps - rows.get(1).steps);
                } else if (i > 0) {
                    s += String.format(", %,d steps behind %s.", rows.get(i - 1).steps - rows.get(i).steps,
                            rows.get(i - 1).name);
                } else {
                    s += ". Invite friends to compete!";
                }
                return s;
            }
            return "";
        }

        void show(Player me, LocalDate today) {
            report(me, today);
            String wk = Player.weekKey(today);
            List<Entry> rows = standings(wk);

            System.out.println("\n--- Weekly Leaderboard (" + wk + ") ---");
            for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                Entry e = rows.get(i);
                String prize = i < PRIZES.length ? "  [prize: " + PRIZES[i] + " coins]" : "";
                String you = e.name.equals(me.getName()) ? "  <- you" : "";
                System.out.printf("%d. %-12s %7d steps%s%s%n", i + 1, e.name, e.steps, prize, you);
            }
            System.out.println(statusLine(me, today));

            int daysLeft = DayOfWeek.SUNDAY.getValue() - today.getDayOfWeek().getValue();
            String timeLeft = daysLeft == 0 ? "today is the last day" : daysLeft + " day(s) left";
            System.out.printf("Top 3 win prize coins when the week ends (%s). You need %,d+ steps and at least %d students.%n",
                    timeLeft, MIN_STEPS_FOR_PRIZE, MIN_ENTRANTS);
        }

        /** Pays out a finished week's podium prize to this student, if they earned one. */
        void settleWeek(Player p, String weekKey) {
            List<Entry> rows = standings(weekKey);
            if (rows.size() < MIN_ENTRANTS) return;
            for (int i = 0; i < rows.size(); i++) {
                Entry e = rows.get(i);
                if (!e.name.equals(p.getName())) continue;
                int rank = i + 1;
                System.out.println("\n*** Last week's final standings: you finished #" + rank
                        + " of " + rows.size() + " ***");
                if (rank > PRIZES.length) return;
                if (e.steps < MIN_STEPS_FOR_PRIZE) {
                    System.out.printf("You needed %,d steps to win a prize but had %,d. Next week!%n",
                            MIN_STEPS_FOR_PRIZE, e.steps);
                    return;
                }
                p.awardCoins(PRIZES[rank - 1]);
                System.out.println("Podium prize: +" + PRIZES[rank - 1] + " coins!");
                return;
            }
        }
    }

    // ======================= Checkpoints =======================
    static class Checkpoint {
        private final String name;
        private final int bonusCoins;
        Checkpoint(String name, int bonusCoins) {
            this.name = name;
            this.bonusCoins = bonusCoins;
        }
        String getName() { return name; }
        int getBonusCoins() { return bonusCoins; }
    }

    /** Decides whether the player is really at a checkpoint. Swap in a GPS version later. */
    interface LocationVerifier {
        boolean isAt(Checkpoint c);
    }

    /** Demo-only verifier that just asks. A real app would compare phone GPS to the location. */
    static class HonorSystemVerifier implements LocationVerifier {
        private final Scanner in;
        HonorSystemVerifier(Scanner in) { this.in = in; }
        public boolean isAt(Checkpoint c) {
            System.out.print("Are you at " + c.getName() + " right now? (y/n) ");
            return in.nextLine().trim().equalsIgnoreCase("y");
        }
    }

    static class CheckpointBoard {
        private static final int EXPLORER_BONUS = 100;

        private final List<Checkpoint> checkpoints = List.of(
                new Checkpoint("Library", 40),
                new Checkpoint("Student Union", 40),
                new Checkpoint("Campus Garden", 60),
                new Checkpoint("Sports Field", 80)
        );
        private final LocationVerifier verifier;

        CheckpointBoard(LocationVerifier verifier) { this.verifier = verifier; }

        int size() { return checkpoints.size(); }

        void show(Player p) {
            System.out.println("\n--- Campus Checkpoints (reset daily) ---");
            for (int i = 0; i < checkpoints.size(); i++) {
                Checkpoint c = checkpoints.get(i);
                String status = p.hasVisited(c.getName()) ? "[visited]" : "+" + c.getBonusCoins() + " coins";
                System.out.println((i + 1) + ") " + c.getName() + " " + status);
            }
            System.out.println("Visit all " + checkpoints.size() + " in a day for a +" + EXPLORER_BONUS + " Explorer bonus.");
        }

        void visit(int index, Player p) {
            Checkpoint c = checkpoints.get(index);
            if (p.hasVisited(c.getName())) {
                System.out.println("You already checked in at " + c.getName() + " today.");
                return;
            }
            if (!verifier.isAt(c)) {
                System.out.println("Walk over to " + c.getName() + " first, then check in!");
                return;
            }
            p.markVisited(c.getName());
            p.addEnergy(1);
            int earned = p.addCoins(c.getBonusCoins());
            System.out.println("Checked in at " + c.getName() + "! +1 energy, +" + earned + " coins");
            if (p.getVisitedCount() == checkpoints.size()) {
                int bonus = p.addCoins(EXPLORER_BONUS);
                System.out.println("Explorer bonus! You visited every checkpoint today: +" + bonus + " coins");
            }
        }
    }

    // ======================= Minigames =======================
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

    // ======================= Menu helpers =======================
    static int parse(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Brings the player up to date with today, settling last week's prize if a week just ended. */
    static void applySync(Player p, StepCounter c, LocalDate today, Leaderboard board) {
        Player.SyncResult result = p.syncDate(today, c);
        if (result.streakBroken) {
            System.out.println("You missed a daily goal, so your streak was reset. Start a new one today!");
        }
        if (result.finishedWeek != null) {
            board.settleWeek(p, result.finishedWeek);
        }
        board.report(p, today);
    }

    static void showStats(Player p, StepCounter c, LocalDate today, Leaderboard board) {
        System.out.println("\n--- " + p.getName() + " (" + today + ") ---");
        System.out.printf("Steps today: %d / %d%n", c.getStepsToday(), c.getDailyGoal());
        System.out.printf("Steps until next energy: %d%n",
                StepCounter.STEPS_PER_ENERGY - c.getUnconverted());
        System.out.printf("Steps this week: %d%n", p.getWeekSteps());
        System.out.printf("Energy: %d | Coins: %d | Streak: %d (x%.2f)%n",
                p.getEnergy(), p.getCoins(), p.getStreak(), p.getMultiplierExact());
        if (p.isStreakAtRisk(c)) {
            System.out.println("Streak at risk! Reach " + c.getDailyGoal() + " steps today to keep it.");
        }
        String status = board.statusLine(p, today);
        if (!status.isEmpty()) System.out.println(status);
    }

    static void logSteps(int steps, Player p, StepCounter c, LocalDate today, Leaderboard board) {
        if (steps <= 0) { System.out.println("No steps logged."); return; }
        int accepted = c.add(steps);
        if (accepted < steps) {
            System.out.println("Only " + accepted + " of those steps were counted (daily and per-entry limits).");
        }
        p.addWeekSteps(accepted);
        int energy = c.convertToEnergy();
        p.addEnergy(energy);
        System.out.println("+" + accepted + " steps, +" + energy + " energy.");
        if (c.checkGoalReached()) {
            p.recordGoalReached(today);
            System.out.printf("Daily goal reached! Streak is now %d (coin multiplier x%.2f).%n",
                    p.getStreak(), p.getMultiplierExact());
        }
        board.report(p, today);
        String status = board.statusLine(p, today);
        if (!status.isEmpty()) System.out.println(status);
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

    static void checkpointMenu(CheckpointBoard board, Player p, Scanner in) {
        board.show(p);
        System.out.print("Check in at which checkpoint (0 to go back)? ");
        int choice = parse(in.nextLine());
        if (choice < 1 || choice > board.size()) return;
        board.visit(choice - 1, p);
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

    static void printMenu() {
        System.out.println("\n1) Log steps   2) Simulate a walk   3) Play minigame   4) Stats");
        System.out.println("5) Checkpoints 6) Reward shop       7) My vouchers    8) Leaderboard");
        System.out.println("9) Skip ahead N days (demo)         0) Save & quit");
        System.out.print("> ");
    }

    // ======================= Main =======================
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        GameClock clock = new GameClock();
        StepCounter counter = new StepCounter(5000);
        Leaderboard board = new Leaderboard("stepquest_leaderboard.properties");

        System.out.print("Your name (must be unique among students): ");
        String typed = in.nextLine().trim();
        if (typed.isEmpty()) typed = "Student";

        SaveManager saves = new SaveManager(typed);
        Player player = saves.load(counter);
        if (player != null && player.isDatedAfter(clock.today())) {
            System.out.println("That save came from the demo date-skip, so it was reset.");
            player = null;
        }
        if (player == null) {
            player = new Player(typed);
        } else {
            System.out.println("Welcome back, " + player.getName() + "!");
        }
        applySync(player, counter, clock.today(), board);
        saves.save(player, counter);

        RewardShop shop = new RewardShop();
        CheckpointBoard checkpoints = new CheckpointBoard(new HonorSystemVerifier(in));
        StepSource manual = new ManualStepSource(in);
        StepSource random = new RandomWalkSource();
        List<MiniGame> games = List.of(new LuckyDice(), new NumberGuess(), new MemoryDigits());

        boolean running = true;
        while (running) {
            printMenu();
            switch (in.nextLine().trim()) {
                case "1" -> logSteps(manual.readSteps(), player, counter, clock.today(), board);
                case "2" -> {
                    int steps = random.readSteps();
                    System.out.println("You wandered around and walked " + steps + " steps.");
                    logSteps(steps, player, counter, clock.today(), board);
                }
                case "3" -> playMenu(games, player, in);
                case "4" -> showStats(player, counter, clock.today(), board);
                case "5" -> checkpointMenu(checkpoints, player, in);
                case "6" -> shopMenu(shop, player, in);
                case "7" -> showWallet(player);
                case "8" -> board.show(player, clock.today());
                case "9" -> {
                    System.out.print("Skip how many days (1-14)? ");
                    int days = parse(in.nextLine());
                    if (days < 1 || days > 14) {
                        System.out.println("Pick a number from 1 to 14.");
                    } else {
                        clock.advanceDays(days);
                        System.out.println("Skipped ahead to " + clock.today() + ".");
                        applySync(player, counter, clock.today(), board);
                    }
                }
                case "0" -> running = false;
                default -> System.out.println("Pick 0-9.");
            }
            saves.save(player, counter);
        }
        System.out.println("Progress saved. Thanks for walking, " + player.getName() + "!");
    }
}
