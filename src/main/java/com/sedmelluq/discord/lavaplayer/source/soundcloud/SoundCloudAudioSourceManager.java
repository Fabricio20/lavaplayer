package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio source manager that implements finding SoundCloud tracks based on URL.
 */
public class SoundCloudAudioSourceManager implements AudioSourceManager, HttpConfigurable {
  private static final Logger log = LoggerFactory.getLogger(SoundCloudAudioSourceManager.class);

  private static final int DEFAULT_SEARCH_RESULTS = 10;
  private static final int MAXIMUM_SEARCH_RESULTS = 200;

  private static final String CHARSET = "UTF-8";
  private static final String TRACK_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)(?:m\\.|)soundcloud\\.com/([a-zA-Z0-9-_]+)/([a-zA-Z0-9-_]+)(?:\\?.*|)$";
  private static final String UNLISTED_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)(?:m\\.|)soundcloud\\.com/([a-zA-Z0-9-_]+)/([a-zA-Z0-9-_]+)/s-([a-zA-Z0-9-_]+)(?:\\?.*|)$";
  private static final String PLAYLIST_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)(?:m\\.|)soundcloud\\.com/([a-zA-Z0-9-_]+)/sets/([a-zA-Z0-9-_]+)(?:\\?.*|)$";
  private static final String LIKED_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)(?:m\\.|)soundcloud\\.com/([a-zA-Z0-9-_]+)/likes/?(?:\\?.*|)$";
  private static final String LIKED_USER_URN_REGEX = "\"urn\":\"soundcloud:users:([0-9]+)\",\"username\":\"([^\"]+)\"";
  private static final String SEARCH_PREFIX = "scsearch";
  private static final String SEARCH_PREFIX_DEFAULT = "scsearch:";
  private static final String SEARCH_REGEX = SEARCH_PREFIX + "\\[([0-9]{1,9}),([0-9]{1,9})\\]:\\s*(.*)\\s*";

  private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
  private static final Pattern unlistedUrlPattern = Pattern.compile(UNLISTED_URL_REGEX);
  private static final Pattern playlistUrlPattern = Pattern.compile(PLAYLIST_URL_REGEX);
  private static final Pattern likedUrlPattern = Pattern.compile(LIKED_URL_REGEX);
  private static final Pattern likedUserUrnPattern = Pattern.compile(LIKED_USER_URN_REGEX);
  private static final Pattern searchPattern = Pattern.compile(SEARCH_REGEX);

  private final HttpInterfaceManager httpInterfaceManager;
  private final SoundCloudClientIdTracker clientIdTracker;
  private final boolean allowSearch;

  /**
   * Create an instance with default settings.
   */
  public SoundCloudAudioSourceManager() {
    this(true);
  }

  /**
   * Create an instance.
   * @param allowSearch Whether to allow search queries as identifiers
   */
  public SoundCloudAudioSourceManager(boolean allowSearch) {
    httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    clientIdTracker = new SoundCloudClientIdTracker(httpInterfaceManager);
    httpInterfaceManager.setHttpContextFilter(new SoundCloudHttpContextFilter(clientIdTracker));

    this.allowSearch = allowSearch;
  }

  @Override
  public String getSourceName() {
    return "soundcloud";
  }

  @Override
  public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
    AudioItem track = processAsSingleTrack(reference);

    if (track == null) {
      track = processAsPlaylist(reference);
    }

    if (track == null && allowSearch) {
      track = processAsSearchQuery(reference);
    }

    return track;
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) {
    // No extra information to save
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
    return new SoundCloudAudioTrack(trackInfo, this);
  }

  @Override
  public void shutdown() {
    // Nothing to shut down
  }

  public String getClientId() {
    return clientIdTracker.getClientId();
  }

  /**
   * @param trackId ID of the track
   * @return URL to use for streaming the track.
   */
  public String getTrackUrlFromId(String trackId) {
    String[] parts = trackId.split("\\|");

    if (parts.length < 2) {
      return "https://api.soundcloud.com/tracks/" + trackId + "/stream";
    } else {
      return "https://api.soundcloud.com/tracks/" + parts[0] + "/stream?secret_token=" + parts[1];
    }
  }

  /**
   * @return Get an HTTP interface for a playing track.
   */
  public HttpInterface getHttpInterface() {
    return httpInterfaceManager.getInterface();
  }

  @Override
  public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
    httpInterfaceManager.configureRequests(configurator);
  }

  @Override
  public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
    httpInterfaceManager.configureBuilder(configurator);
  }

  private AudioTrack processAsSingleTrack(AudioReference reference) {
    String url = nonMobileUrl(reference.identifier);

    Matcher trackUrlMatcher = trackUrlPattern.matcher(url);
    if (trackUrlMatcher.matches() && !"likes".equals(trackUrlMatcher.group(2))) {
      return loadFromTrackPage(url, null);
    }

    Matcher unlistedUrlMatcher = unlistedUrlPattern.matcher(url);
    if (unlistedUrlMatcher.matches()) {
      return loadFromTrackPage(url, "s-" + unlistedUrlMatcher.group(3));
    }

    return null;
  }

  private AudioItem processAsPlaylist(AudioReference reference) {
    String url = nonMobileUrl(reference.identifier);

    if (playlistUrlPattern.matcher(url).matches()) {
      return loadFromSet(url);
    } else if (likedUrlPattern.matcher(url).matches()) {
      return loadFromLikedTracks(url);
    } else {
      return null;
    }
  }

  private AudioTrack loadFromTrackPage(String trackWebUrl, String secretToken) {
    try (HttpInterface httpInterface = getHttpInterface()) {
      JsonBrowser trackInfoJson = loadTrackInfoFromJson(loadPageConfigJson(httpInterface, trackWebUrl));
      return buildAudioTrack(trackInfoJson, secretToken);
    } catch (IOException e) {
      throw new FriendlyException("Loading track from SoundCloud failed.", SUSPICIOUS, e);
    }
  }

  private AudioTrack buildAudioTrack(JsonBrowser trackInfoJson, String secretToken) {
    String trackId = trackInfoJson.get("id").text();

    for (JsonBrowser transcoding : trackInfoJson.safeGet("media").safeGet("transcodings").values()) {
      JsonBrowser format = transcoding.safeGet("format");

      if (format.safeGet("protocol").text().equals("hls") && format.safeGet("mime_type").text().contains("audio/ogg")) {
        return buildAudioTrackWithIdentifier(trackInfoJson, "O:" + transcoding.safeGet("url").text());
      }
    }

    return buildAudioTrackWithIdentifier(trackInfoJson, secretToken != null ? trackId + "|" + secretToken : trackId);
  }

  private AudioTrack buildAudioTrackWithIdentifier(JsonBrowser trackInfoJson, String identifier) {
    AudioTrackInfo trackInfo = new AudioTrackInfo(
        trackInfoJson.get("title").text(),
        trackInfoJson.get("user").get("username").text(),
        trackInfoJson.get("duration").as(Integer.class),
        identifier,
        false,
        trackInfoJson.get("permalink_url").text(),
        Collections.singletonMap("artworkUrl", trackInfoJson.get("artwork_url").text())
    );

    return new SoundCloudAudioTrack(trackInfo, this);
  }

  private JsonBrowser loadPageConfigJson(HttpInterface httpInterface, String url) throws IOException {
    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
      int statusCode = response.getStatusLine().getStatusCode();

      if (statusCode == HttpStatus.SC_NOT_FOUND) {
        throw new FriendlyException("That track does not exist.", COMMON, null);
      } else if (statusCode != HttpStatus.SC_OK) {
        throw new IOException("Invalid status code for video page response: " + statusCode);
      }

      String html = IOUtils.toString(response.getEntity().getContent(), Charset.forName(CHARSET));
      String configJson = DataFormatTools.extractBetween(html, "catch(t){}})},", ");</script>");

      if (configJson == null) {
        throw new FriendlyException("This url does not appear to be a playable track.", SUSPICIOUS, null);
      }

      return JsonBrowser.parse(configJson);
    }
  }

  private JsonBrowser loadTrackInfoFromJson(JsonBrowser json) {
    for (JsonBrowser value : json.values()) {
      for (JsonBrowser entry : value.safeGet("data").values()) {
        if (entry.isMap() && "track".equals(entry.get("kind").text())) {
          return entry;
        }
      }
    }

    throw new IllegalStateException("Could not find track information block.");
  }

  private static String nonMobileUrl(String url) {
    if (url.startsWith("https://m.")) {
      return "https://" + url.substring("https://m.".length());
    } else {
      return url;
    }
  }

  private AudioPlaylist loadFromSet(String playlistWebUrl) {
    try (HttpInterface httpInterface = getHttpInterface()) {
      JsonBrowser playlistInfo = loadPlaylistInfoFromJson(loadPageConfigJson(httpInterface, playlistWebUrl));

      return new BasicAudioPlaylist(
          playlistInfo.get("title").text(),
          loadTracksFromPlaylist(httpInterface, playlistInfo, playlistWebUrl),
          null,
          false
      );
    } catch (IOException e) {
      throw new FriendlyException("Loading playlist from SoundCloud failed.", SUSPICIOUS, e);
    }
  }

  private JsonBrowser loadPlaylistInfoFromJson(JsonBrowser json) {
    for (JsonBrowser value : json.values()) {
      for (JsonBrowser entry : value.safeGet("data").values()) {
        if (entry.isMap() && "playlist".equals(entry.get("kind").text())) {
          return entry;
        }
      }
    }

    throw new IllegalStateException("Could not find playlist information block.");
  }

  private List<AudioTrack> loadTracksFromPlaylist(HttpInterface httpInterface, JsonBrowser playlistInfo, String playlistWebUrl) throws IOException {
    List<String> trackIds = loadPlaylistTrackList(playlistInfo);

    return withClientIdRetry(httpInterface,
        response -> handlePlaylistTracksResponse(response, playlistWebUrl, trackIds),
        () -> buildTrackListUrl(trackIds)
    );
  }

  private List<AudioTrack> handlePlaylistTracksResponse(HttpResponse response, String playlistWebUrl, List<String> trackIds) throws IOException {
    List<AudioTrack> tracks = new ArrayList<>();
    int statusCode = response.getStatusLine().getStatusCode();
    if (statusCode != 200) {
      throw new IOException("Invalid status code for track list response: " + statusCode);
    }

    JsonBrowser trackList = JsonBrowser.parse(response.getEntity().getContent());
    int blockedCount = 0;

    for (JsonBrowser trackInfoJson : trackList.values()) {
      if ("BLOCK".equals(trackInfoJson.get("policy").text())) {
        blockedCount++;
      } else {
        tracks.add(buildAudioTrack(trackInfoJson, null));
      }
    }

    if (blockedCount > 0) {
      log.debug("In soundcloud playlist {}, {} tracks were omitted because they are blocked.", playlistWebUrl, blockedCount);
    }

    sortPlaylistTracks(tracks, trackIds);

    return tracks;
  }

  private List<String> loadPlaylistTrackList(JsonBrowser playlistInfo) {
    List<String> trackIds = new ArrayList<>();
    for (JsonBrowser trackInfo : playlistInfo.get("tracks").values()) {
      trackIds.add(trackInfo.get("id").text());
    }
    return trackIds;
  }

  private URI buildTrackListUrl(List<String> trackIds) {
    try {
      StringJoiner joiner = new StringJoiner(",");
      for (String trackId : trackIds) {
        joiner.add(trackId);
      }

      return new URIBuilder("https://api-v2.soundcloud.com/tracks")
          .addParameter("ids", joiner.toString())
          .build();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static void sortPlaylistTracks(List<AudioTrack> tracks, List<String> trackIds) {
    final Map<String, Integer> positions = new HashMap<>();
    for (int i = 0; i < trackIds.size(); i++) {
      positions.put(trackIds.get(i), i);
    }

    tracks.sort(Comparator.comparingInt(o -> getSortPosition(positions, o)));
  }

  private static int getSortPosition(Map<String, Integer> positions, AudioTrack track) {
    return DataFormatTools.defaultOnNull(positions.get(track.getIdentifier()), Integer.MAX_VALUE);
  }

  private AudioItem loadFromLikedTracks(String likedListUrl) {
    try (HttpInterface httpInterface = getHttpInterface()) {
      UserInfo userInfo = findUserIdFromLikedList(httpInterface, likedListUrl);
      if (userInfo == null) {
        return AudioReference.NO_TRACK;
      }

      return extractTracksFromLikedList(loadLikedListForUserId(httpInterface, userInfo), userInfo);
    } catch (IOException e) {
      throw new FriendlyException("Loading liked tracks from SoundCloud failed.", SUSPICIOUS, e);
    }
  }

  private UserInfo findUserIdFromLikedList(HttpInterface httpInterface, String likedListUrl) throws IOException {
    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(likedListUrl))) {
      int statusCode = response.getStatusLine().getStatusCode();

      if (statusCode == 404) {
        return null;
      } else if (statusCode != 200) {
        throw new IOException("Invalid status code for track list response: " + statusCode);
      }

      Matcher matcher = likedUserUrnPattern.matcher(IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8));
      return matcher.find() ? new UserInfo(matcher.group(1), matcher.group(2)) : null;
    }
  }

  private JsonBrowser loadLikedListForUserId(HttpInterface httpInterface, UserInfo userInfo) throws IOException {
    return withClientIdRetry(httpInterface, response -> {
      int statusCode = response.getStatusLine().getStatusCode();

      if (statusCode != 200) {
        throw new IOException("Invalid status code for liked tracks response: " + statusCode);
      }

      return JsonBrowser.parse(response.getEntity().getContent());
    }, () ->
        new URI("https://api-v2.soundcloud.com/users/" + userInfo.id + "/likes?limit=200&offset=0")
    );
  }

  private AudioItem extractTracksFromLikedList(JsonBrowser likedTracks, UserInfo userInfo) {
    List<AudioTrack> tracks = new ArrayList<>();

    for (JsonBrowser item : likedTracks.get("collection").values()) {
      JsonBrowser trackItem = item.get("track");

      if (!trackItem.isNull()) {
        tracks.add(buildAudioTrack(trackItem, null));
      }
    }

    return new BasicAudioPlaylist("Liked by " + userInfo.name, tracks, null, false);
  }

  private static class UserInfo {
    private final String id;
    private final String name;

    private UserInfo(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  private AudioItem processAsSearchQuery(AudioReference reference) {
    if (reference.identifier.startsWith(SEARCH_PREFIX)) {
      if (reference.identifier.startsWith(SEARCH_PREFIX_DEFAULT)) {
        return loadSearchResult(reference.identifier.substring(SEARCH_PREFIX_DEFAULT.length()).trim(), 0, DEFAULT_SEARCH_RESULTS);
      }

      Matcher searchMatcher = searchPattern.matcher(reference.identifier);

      if (searchMatcher.matches()) {
        return loadSearchResult(searchMatcher.group(3), Integer.parseInt(searchMatcher.group(1)), Integer.parseInt(searchMatcher.group(2)));
      }
    }

    return null;
  }

  private AudioItem loadSearchResult(String query, int offset, int rawLimit) {
    int limit = Math.min(rawLimit, MAXIMUM_SEARCH_RESULTS);

    try (HttpInterface httpInterface = getHttpInterface()) {
      return withClientIdRetry(httpInterface,
          response -> loadSearchResultsFromResponse(response, query),
          () -> buildSearchUri(query, offset, limit)
      );
    } catch (IOException e) {
      throw new FriendlyException("Loading search results from SoundCloud failed.", SUSPICIOUS, e);
    }
  }

  private AudioItem loadSearchResultsFromResponse(HttpResponse response, String query) throws IOException {
    try {
      JsonBrowser searchResults = JsonBrowser.parse(response.getEntity().getContent());
      return extractTracksFromSearchResults(query, searchResults);
    } finally {
      EntityUtils.consumeQuietly(response.getEntity());
    }
  }

  private URI buildSearchUri(String query, int offset, int limit) {
    try {
      return new URIBuilder("https://api-v2.soundcloud.com/search/tracks")
          .addParameter("q", query)
          .addParameter("offset", String.valueOf(offset))
          .addParameter("limit", String.valueOf(limit))
          .build();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private <T> T withClientIdRetry(HttpInterface httpInterface, ResponseHandler<T> handler, URIProvider uriProvider) throws IOException {
    try {
      HttpResponse response = httpInterface.execute(new HttpGet(uriProvider.provide()));
      int statusCode = response.getStatusLine().getStatusCode();

      try {
        if (statusCode != 401) {
          return handler.handle(response);
        }
      } finally {
        EntityUtils.consumeQuietly(response.getEntity());
      }

      response = httpInterface.execute(new HttpGet(uriProvider.provide()));

      try {
        return handler.handle(response);
      } finally {
        EntityUtils.consumeQuietly(response.getEntity());
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private AudioItem extractTracksFromSearchResults(String query, JsonBrowser searchResults) {
    List<AudioTrack> tracks = new ArrayList<>();

    for (JsonBrowser item : searchResults.get("collection").values()) {
      if (!item.isNull()) {
        tracks.add(buildAudioTrack(item, null));
      }
    }

    return new BasicAudioPlaylist("Search results for: " + query, tracks, null, true);
  }

  private interface ResponseHandler<T> {
    T handle(HttpResponse response) throws IOException;
  }

  private interface URIProvider {
    URI provide() throws URISyntaxException;
  }
}