package com.mihir.traffic.ruleservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Rule Management Service, the control plane's front door. */
@SpringBootApplication
public class RuleServiceApplication {

  protected RuleServiceApplication() {
    // Spring instantiates this class; it is not meant to be constructed directly.
  }

  /**
   * Boots the service.
   *
   * @param args command-line arguments passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(RuleServiceApplication.class, args);
  }
}
