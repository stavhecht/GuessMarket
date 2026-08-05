package ui;

import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * The only class in the application that reads from the console.
 *
 * <p>Every method re-prompts until it gets something valid, so a typo can never
 * propagate into the engine or crash the program.
 */
public class InputReader {

    private final Scanner scanner = new Scanner(System.in);

    public String readLine(String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = nextLine();
            if (!line.isBlank()) {
                return line.trim();
            }
            System.out.println("Please enter a value.");
        }
    }

    public int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = nextLine().trim();
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("'" + line + "' is not a whole number. Try again.");
            }
        }
    }

    /** Reads a menu selection in [min, max] inclusive. */
    public int readMenuChoice(int min, int max) {
        while (true) {
            int choice = readInt("Your choice: ");
            if (choice >= min && choice <= max) {
                return choice;
            }
            System.out.println("Please enter a number between " + min + " and " + max + ".");
        }
    }

    public long readPositiveLong(String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = nextLine().trim();
            try {
                long value = Long.parseLong(line);
                if (value > 0) {
                    return value;
                }
                System.out.println("The amount must be greater than 0.");
            } catch (NumberFormatException e) {
                System.out.println("'" + line + "' is not a whole number. Try again.");
            }
        }
    }

    /** Treats a closed stdin as an exit rather than letting the Scanner throw. */
    private String nextLine() {
        try {
            return scanner.nextLine();
        } catch (NoSuchElementException e) {
            System.out.println();
            System.exit(0);
            return "";   // unreachable; keeps the compiler happy
        }
    }
}
