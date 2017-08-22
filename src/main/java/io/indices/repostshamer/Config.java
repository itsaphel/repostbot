package io.indices.repostshamer;

public class Config {
    public String source;
    public String[] targets;
    public String response;
    public Credentials credentials;
    public Database database;

    public class Credentials {
        public String username;
        public String password;
        public String client_id;
        public String client_secret;
    }

    public class Database {
        public String host;
        public int port;
        public String username;
        public String password;
        public String name;
    }
}
