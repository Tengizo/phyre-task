# phyre-task

Task for Phyre For Generating webSocket client I used java library https://github.com/TooTallNate/Java-WebSocket.

WebSocketClient is as good as it's needed for this task. doesn't support sub-protocols and extensions. working on text
Messages only.

to start Application run:

    mvn package
    java -jar target\Phyre-task-1.0-SNAPSHOT.jar

Or start by Main Class: com.phyre.Main

OrderBook takes List of Exchanges, and keeps track of all of them.
Currently, we have only 2 Exchange implementations Bitfinex and Kraken.

