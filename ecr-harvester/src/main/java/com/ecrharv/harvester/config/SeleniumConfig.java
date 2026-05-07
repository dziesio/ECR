package com.ecrharv.harvester.config;

import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Slf4j
@Configuration
public class SeleniumConfig {

    @Value("${selenium.headless:true}")
    private boolean headless;

    @Value("${selenium.page-load-timeout:30}")
    private int pageLoadTimeout;

    public WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();

        if (headless) {
            options.addArguments("--headless");
        }

        options.addArguments(
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--disable-extensions",
                "--disable-infobars",
                "--single-process",
                "--remote-debugging-port=0",
                "--disable-features=VizDisplayCompositor",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );

        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeout));

        if (driver instanceof JavascriptExecutor js) {
            js.executeScript(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
            );
        }

        log.info("ChromeDriver created (headless={})", headless);
        return driver;
    }
}
