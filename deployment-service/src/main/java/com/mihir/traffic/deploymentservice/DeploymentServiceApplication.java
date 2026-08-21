package com.mihir.traffic.deploymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the deployment service. */
@SpringBootApplication
public class DeploymentServiceApplication {

  protected DeploymentServiceApplication() {
    // Spring Boot instantiates this; it is not meant to be constructed directly.
  }

  /**
   * Starts the service.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(DeploymentServiceApplication.class, args);
  }
}
