package ui;

import engine.service.MarketEngine;
import ui.console.ConsoleApp;
import ui.desktop.DesktopApp;

public class Main {

    public static void main(String[] args) {
        int exN = 2;     //choose version of exercise
        if (exN == 1) {
            new ConsoleApp(new MarketEngine()).run();
        }
        if (exN == 2) {
            new DesktopApp(new MarketEngine()).run();
        }

    }
}
