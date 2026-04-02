# Study Sync

## Description
Study Sync is a Java-based tool that connects a user's Canvas calendar feed to a system (such as a Discord bot). It validates the Canvas iCal feed URL, verifies it, and stores it for later use.

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

mvn exec:java -Dexec.mainClass=StudySyncBot

A Discord bot token is required to run the bot.
No Discord token is included for security reasons.
Discord tokens must be created from the Discord Developer portal.

## Tests
Run unit tests:

mvn test

## Statistic Analysis (generates figures)
javac StudySyncData.java

java StudySyncData.java

## Continuous Integration
This project uses GitHub Actions for continuous integration. The project is automatically built and tested on each push to the repository. You can view CI results in the "Actions" tab on GitHub.

## Bot Commands
"/setup" - to link a user's Canvas calendar to the bot
"/unlink" - to unlink a user's calendar
"/assignments" - to show all upcoming assignments and their number
"/today" - for what assignments are due today
"/edit" - specify an assignment id to hide from the bot
"/unhide" - restore all hidden assignments
"/frequency" - set the number of hours between notifications

## Bot Link
A link to our working bot currently using one of our Canvas APIs.
https://discord.com/oauth2/authorize?client_id=1488939207983366244
