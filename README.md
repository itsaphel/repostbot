# Repost Bot

A bot that watches for reposts on reddit.

To configure, add the necessary information into [config.json](https://github.com/itsaphel/repostbot/blob/master/src/main/resources/config.json) and place it in the same directory as the .jar file.

You will need to create a MySQL database for this to work. You will also need a reddit account with API access.

## Philosophy and Mission

This small project was open-sourced 25/02/2018. It was created for use on reddit to see when the same image is reposted to another subreddit, using "listening" subreddit and "watching" subreddits, logging entries into a database.

It has a critical limitation, and that is that it uses a hash to decide if the content is matching. Due to different types of compression done when an image is served, this is not always reliable and the hash will usually be different because the underlying content data is often going to be different. Hence, it only really works if the same URL is reposted, or if the same image is uploaded to the same sharing website.

Ideally, comparisons of the image, storing visuals of the image or, better yet, using machine learning to detect reposts would be a better method, but this wasn't something I had time or the motivation to work on. The project is available for archival purposes, it is functional in the capacity listed above if you wish to use it.
