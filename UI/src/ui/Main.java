package ui;

import engine.service.MarketEngine;

public class Main {

    public static void main(String[] args) {
        new ConsoleApp(new MarketEngine()).run();
    }
}
