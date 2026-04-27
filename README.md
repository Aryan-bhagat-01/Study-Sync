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

## Data Analysis
Creates Bot to Canvas API Connection Time graph:

mvn test -Dtest=ConnectionTimes

Creates Before and After Study Sync - On-Time and Late Submissions graphs:

mvn test -Dtest=StudentSubmissions

## Continuous Integration
This project uses GitHub Actions for continuous integration. The project is automatically built and tested on each push to the repository. You can view CI results in the "Actions" tab on GitHub.

----------------------------------------------------------------------------------------------------------------------------------------------------------
## Bot Setup on Discord (through Direct Message)
Open this link in your browser: https://discord.com/oauth2/authorize?client_id=1488939207983366244

Click Add to my apps

Click Authorize

Open Discord and click Find or start a conversation at the top left

Type StudySync and click on it

In the chat box type "/" and you'll see all commands appear

Click "/setup" and paste your Canvas iCal URL

Hit Enter — the bot will confirm and start sending you reminders

## Bot Setup on Discord (through Server)
Open this link in your browser: https://discord.com/oauth2/authorize?client_id=1488939207983366244

Select your server from the Add to Server dropdown

Click Authorize

Go to Discord and open the server

Go to the channel you want assignments posted in

Type /setup and paste your Canvas iCal URL

Hit Enter — the bot confirms and starts posting hourly

## Getting your Canvas URL
Log into Canvas

Click Calendar on the left sidebar

Scroll to the very bottom left of the page

Click Calendar Feed

Copy the entire link

## Bot Commands
"/setup" - Link a user’s Canvas iCal feed URL to the bot.

"/unlink" - Remove the linked Canvas iCal feed from the bot.

"/assignments <number> " - By default show the first ten upcoming assignments with just /assignments, their dates and their numbers.         Ex: /assignments 5 will show the 5 upcoming assignments.

"/today" - Show assignments and assignment dates due by midnight.

"/upcoming" - Show assignments due this week

"/overdue" - Show overdue assignments.

"/complete <number>" - Mark an assignment as complete using its corresponding number given by the /assignments list.                          Ex: /complete <4> marks the 4th assignment on the list as complete.

"/delete <number>" - Hide an assignment by number.                                                                                             Ex: /delete 4 deletes 4th assignment on the list

"/unhide" - Restore all hidden and completed assignments.

"/frequency <hours>" - Change how often the bot posts assignment reminders in hours.                                                         Ex: /frequency <4> notifies about the most upcoming assignment every 4 hours.


