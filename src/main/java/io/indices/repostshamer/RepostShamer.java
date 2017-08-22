package io.indices.repostshamer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import net.dean.jraw.RedditClient;
import net.dean.jraw.http.UserAgent;
import net.dean.jraw.http.oauth.Credentials;
import net.dean.jraw.http.oauth.OAuthData;
import net.dean.jraw.http.oauth.OAuthException;
import net.dean.jraw.managers.AccountManager;
import net.dean.jraw.models.Listing;
import net.dean.jraw.models.Submission;
import net.dean.jraw.paginators.Sorting;
import net.dean.jraw.paginators.SubredditPaginator;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.xml.bind.DatatypeConverter;

public class RepostShamer {
    private static final Logger logger = Logger.getLogger(RepostShamer.class.getName());
    private Config config;
    private Gson gson;
    private static final UserAgent userAgent = UserAgent.of("script", "io.indices.repostshamer", "v0.1.3", "WatcherOnTheRepost");
    private static final RedditClient redditClient = new RedditClient(userAgent);
    private static Credentials credentials;
    private static OAuthData oAuthData;
    private Connection connection;
    private List<String> shamedIds = new ArrayList<>();

    public static void main(String[] args) throws FileNotFoundException {
        new RepostShamer().start();
    }

    public void start() throws FileNotFoundException {
        gson = new GsonBuilder().setPrettyPrinting().create();
        Path currentDir = Paths.get(".").toAbsolutePath().normalize();
        config = gson.fromJson(new JsonReader(new FileReader(currentDir.resolve("config.json").toFile())), Config.class);

        try {
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + config.database.host + ":" + config.database.port + "/" + config.database.name,
                    config.database.username,
                    config.database.password);
        } catch (SQLException e) {
            logger.severe("Exception initialising database connection! Terminating application.");
            e.printStackTrace();
            System.exit(0);
        }
        createTables();

        credentials = Credentials.script(config.credentials.username, config.credentials.password, config.credentials.client_id, config.credentials.client_secret);
        try {
            oAuthData = redditClient.getOAuthHelper().easyAuth(credentials);
            redditClient.authenticate(oAuthData);
        } catch (OAuthException e) {
            logger.severe("Credentials exception! Terminating application.");
            e.printStackTrace();
            System.exit(0);
        }

        Executors.newScheduledThreadPool(1).schedule(() -> {
            System.out.println("Refreshing token...");
            try {
                oAuthData = redditClient.getOAuthHelper().refreshToken(credentials);
                redditClient.authenticate(oAuthData);
            } catch (OAuthException e) {
                e.printStackTrace();
            }
        }, 30, TimeUnit.MINUTES);

        //Runtime.getRuntime().addShutdownHook(new Thread(() -> redditClient.getOAuthHelper().revokeAccessToken()));

        loadProcessed();
        startThreads();
    }

    public String getHashFromUrl(String url) {
        String hash = null;

        try {
            InputStream is = new URL(url).openStream();
            MessageDigest sha1 = MessageDigest.getInstance("SHA1");

            try {
                is = new DigestInputStream(is, sha1);
                if (ImageIO.read(is) != null) {
                    byte[] ignoredBuffer = new byte[8 * 1024];
                    while (is.read(ignoredBuffer) > 0) {
                    }
                    byte[] digest = sha1.digest();
                    hash = DatatypeConverter.printHexBinary(digest);
                }
            } finally {
                is.close();
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            // suppress exceptions
        }

        return hash;
    }

    public void startThreads() {
        // fetch things from source
        new Thread(() -> {
            while (true) {
                try {
                    processSubmissions(config.source, 100);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(45 * 1000L);
                } catch (InterruptedException e) {
                }
            }
        }).start();

        // watch things on target
        new Thread(() -> {
            try {
                Thread.sleep(30 * 1000L);
            } catch (InterruptedException e) {
            }

            while (true) {
                try {
                    String[] extraTargets = Arrays.copyOfRange(config.targets, 1, config.targets.length);
                    SubredditPaginator targetPaginator = new SubredditPaginator(redditClient, config.targets[0], extraTargets);
                    targetPaginator.setSorting(Sorting.NEW);
                    targetPaginator.setLimit(100);

                    Listing<Submission> submissions = targetPaginator.next();
                    submissions.forEach(submission -> {
                        if (!submission.isSelfPost()) {
                            String title = submission.getTitle().toLowerCase();

                            if (shamedIds.contains(submission.getId()) ||
                                    title.contains("x-post") || title.contains("xpost") || title.contains("crosspost")) {
                                return;
                            }

                            String hash = getHashFromUrl(submission.getUrl());
                            if (hash != null) {
                                shame(submission, hash);
                            }
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(60 * 1000L);
                } catch (InterruptedException e) {
                }
            }
        }).start();
    }

    private void processSubmissions(String source, int limit) {
        SubredditPaginator sourcePaginator = new SubredditPaginator(redditClient, source);
        sourcePaginator.setSorting(Sorting.NEW);
        sourcePaginator.setLimit(limit);

        Listing<Submission> submissions = sourcePaginator.next();
        submissions.forEach(submission -> {
            if (!submission.isSelfPost()) {
                String hash = getHashFromUrl(submission.getUrl());

                if (hash != null) {
                    saveSubmission(submission, hash);
                }
            }
        });
    }

    private void saveSubmission(Submission submission, String hash) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT IGNORE INTO submissions(id, author, content_url, post_url, created_at, hash) VALUES(?, ?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, submission.getId());
            preparedStatement.setString(2, submission.getAuthor());
            preparedStatement.setString(3, submission.getUrl());
            preparedStatement.setString(4, submission.getPermalink());
            preparedStatement.setDate(5, new java.sql.Date(submission.getCreated().getTime()));
            preparedStatement.setString(6, hash);

            preparedStatement.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void shame(Submission submission, String hash) {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM `submissions` WHERE hash = ? LIMIT 1");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO reposts (id, author, subreddit, content_url, post_url, original_content_id, created_at, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            query.setString(1, hash);
            ResultSet results = query.executeQuery();

            if (results != null && results.next()) {
                if (results.getString("id").equals(submission.getId()) || results.getString("author").equals(submission.getAuthor())) {
                    return;
                }

                // shame
                System.out.println("Shaming /u/" + submission.getAuthor() + " for reposting to " + submission.getId() + " in /r/" + submission.getSubredditName());
                String response = config.response
                        .replace("{author}", results.getString("author"))
                        .replace("{url}", results.getString("post_url"));
                new AccountManager(redditClient).reply(submission, response);

                insert.setString(1, submission.getId());
                insert.setString(2, submission.getAuthor());
                insert.setString(3, submission.getSubredditName()); // v0.1.1
                insert.setString(4, submission.getUrl());
                insert.setString(5, submission.getPermalink());
                insert.setString(6, results.getString("id"));
                insert.setDate(7, new java.sql.Date(submission.getCreated().getTime()));
                insert.setString(8, hash);
                insert.execute();

                shamedIds.add(submission.getId());
                if (shamedIds.size() > 200) {
                    shamedIds.remove(0);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadProcessed() {
        try (Statement statement = connection.createStatement()) {
            ResultSet results = statement.executeQuery("SELECT * FROM reposts ORDER BY created_at DESC LIMIT 100");

            if (results != null) {
                while (results.next()) {
                    shamedIds.add(results.getString("id"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createTables() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `submissions` (" +
                    "`id` VARCHAR(8) NOT NULL," +
                    "`author` VARCHAR(20) NOT NULL," +
                    "`content_url` VARCHAR(500) NOT NULL," +
                    "`post_url` VARCHAR(500) NOT NULL," +
                    "`created_at` VARCHAR(500) NOT NULL," +
                    "`hash` VARCHAR(40) NOT NULL," +
                    "PRIMARY KEY (id)," +
                    "UNIQUE (hash)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `reposts` (" +
                    "`id` VARCHAR(8) NOT NULL," +
                    "`author` VARCHAR(20) NOT NULL," +
                    "`subreddit` VARCHAR(50) NOT NULL," + // v0.1.1
                    "`content_url` VARCHAR(500) NOT NULL," +
                    "`post_url` VARCHAR(500) NOT NULL," +
                    "`original_content_id` VARCHAR(500) NOT NULL," +
                    "`created_at` VARCHAR(500) NOT NULL," +
                    "`hash` VARCHAR(40) NOT NULL," +
                    "PRIMARY KEY (id)," +
                    "UNIQUE (hash)" +
                    ")");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
