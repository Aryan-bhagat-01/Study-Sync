#!/bin/bash
echo "================================"
echo "   Welcome to StudySync Setup   "
echo "================================"
echo ""

if [ ! -f ".env" ]; then
    cp .env.example .env
    echo "Created .env file from .env.example"
else
    echo ".env file already exists, skipping copy"
fi

echo ""
echo "Next steps:"
echo "  1. Open .env and fill in your CANVAS_FEED_URL, DISCORD_TOKEN, and DISCORD_CHANNEL_ID"
echo "  2. Run Canvas setup:   mvn compile exec:java -Dexec.mainClass=\"CanvasSetup\""
echo "  3. Start the bot:      mvn compile exec:java -Dexec.mainClass=\"StudySyncBot\""
echo ""
echo "See README.md for full instructions."