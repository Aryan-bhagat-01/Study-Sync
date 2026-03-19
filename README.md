# Study Sync

## Description
Study Sync is a Java-based tool that connects a user’s Canvas calendar feed to a system (such as a Discord bot). It validates the Canvas iCal feed URL, verifies it, and stores it for later use.

## Requirements
- Java 17
- Maven

## Setup
Clone the repository:

git clone https://github.com/Aryan-bhagat-01/Study-Sync.git
cd Study-Sync

## Build
Build the project using Maven:

mvn clean install

## Run
Run the application:

mvn exec:java

## Tests
Run unit tests:

mvn test

## Continuous Integration
This project uses GitHub Actions for continuous integration. The project is automatically built and tested on each push to the repository. You can view CI results in the "Actions" tab on GitHub.
