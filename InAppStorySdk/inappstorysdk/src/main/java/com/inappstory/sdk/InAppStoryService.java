package com.inappstory.sdk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.inappstory.sdk.eventbus.CsEventBus;
import com.inappstory.sdk.eventbus.CsSubscribe;
import com.inappstory.sdk.eventbus.CsThreadMode;
import com.inappstory.sdk.imageloader.ImageLoader;
import com.inappstory.sdk.listwidget.ListLoadedEvent;
import com.inappstory.sdk.listwidget.StoriesWidgetService;
import com.inappstory.sdk.network.JsonParser;
import com.inappstory.sdk.network.NetworkCallback;
import com.inappstory.sdk.network.NetworkClient;
import com.inappstory.sdk.network.Response;
import com.inappstory.sdk.stories.api.models.CacheFontObject;
import com.inappstory.sdk.stories.api.models.CurrentState;
import com.inappstory.sdk.stories.api.models.StatisticManager;
import com.inappstory.sdk.stories.api.models.StatisticResponse;
import com.inappstory.sdk.stories.api.models.StatisticSendObject;
import com.inappstory.sdk.stories.api.models.StatisticSession;
import com.inappstory.sdk.stories.api.models.Story;
import com.inappstory.sdk.stories.api.models.StoryLinkObject;
import com.inappstory.sdk.stories.api.models.StoryPlaceholder;
import com.inappstory.sdk.stories.api.models.callbacks.GetStoryByIdCallback;
import com.inappstory.sdk.stories.api.models.callbacks.LoadStoriesCallback;
import com.inappstory.sdk.stories.api.models.callbacks.OpenSessionCallback;
import com.inappstory.sdk.stories.cache.Downloader;
import com.inappstory.sdk.stories.cache.StoryDownloader;
import com.inappstory.sdk.stories.events.ContentLoadedEvent;
import com.inappstory.sdk.stories.events.ListVisibilityEvent;
import com.inappstory.sdk.stories.events.LoadFavStories;
import com.inappstory.sdk.stories.events.NextStoryPageEvent;
import com.inappstory.sdk.stories.events.NextStoryReaderEvent;
import com.inappstory.sdk.stories.events.NoConnectionEvent;
import com.inappstory.sdk.stories.events.PauseStoryReaderEvent;
import com.inappstory.sdk.stories.events.PrevStoryReaderEvent;
import com.inappstory.sdk.stories.events.ResumeStoryReaderEvent;
import com.inappstory.sdk.stories.events.StoriesErrorEvent;
import com.inappstory.sdk.stories.events.StoryPageOpenEvent;
import com.inappstory.sdk.stories.events.StoryReaderTapEvent;
import com.inappstory.sdk.stories.managers.OldStatisticManager;
import com.inappstory.sdk.stories.outerevents.ClickOnButton;
import com.inappstory.sdk.stories.outerevents.DislikeStory;
import com.inappstory.sdk.stories.outerevents.FavoriteStory;
import com.inappstory.sdk.stories.outerevents.LikeStory;
import com.inappstory.sdk.stories.outerevents.SingleLoad;
import com.inappstory.sdk.stories.outerevents.SingleLoadError;
import com.inappstory.sdk.stories.serviceevents.DestroyStoriesFragmentEvent;
import com.inappstory.sdk.stories.serviceevents.LikeDislikeEvent;
import com.inappstory.sdk.stories.serviceevents.StoryFavoriteEvent;
import com.inappstory.sdk.stories.statistic.SharedPreferencesAPI;
import com.inappstory.sdk.stories.ui.list.FavoriteImage;
import com.inappstory.sdk.stories.utils.SessionManager;
import com.inappstory.sdk.stories.utils.Sizes;

public class InAppStoryService extends Service {


    public static InAppStoryService getInstance() {
        return INSTANCE;
    }


    public static InAppStoryService INSTANCE;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    void setUserId() {

    }

    void logout() {
        OldStatisticManager.getInstance().closeStatisticEvent(null, true);
        NetworkClient.getApi().statisticsClose(new StatisticSendObject(StatisticSession.getInstance().id,
                InAppStoryManager.getInstance().sendStatistic ? OldStatisticManager.getInstance().statistic : new ArrayList<List<Object>>())).enqueue(new NetworkCallback<StatisticResponse>() {
            @Override
            public void onSuccess(StatisticResponse response) {
            }

            @Override
            public Type getType() {
                return StatisticResponse.class;
            }

            @Override
            public void onError(int code, String message) {
            }
        });
        OldStatisticManager.getInstance().statistic.clear();
        OldStatisticManager.getInstance().statistic = null;
    }

    LoadStoriesCallback loadStoriesCallback;

    public void loadStories(final LoadStoriesCallback callback, final boolean isFavorite) {
        loadStoriesCallback = callback;
        if (isConnected()) {
            SessionManager.getInstance().useOrOpenSession(new OpenSessionCallback() {
                @Override
                public void onSuccess() {
                    NetworkClient.getApi().getStories(StatisticSession.getInstance().id, getTestKey(), isFavorite ? 1 : 0,
                            getTags(), getTestKey(), null).enqueue(isFavorite ? loadCallbackWithoutFav : loadCallback);
                }

                @Override
                public void onError() {
                    CsEventBus.getDefault().post(new StoriesErrorEvent(NoConnectionEvent.LOAD_LIST));
                }
            });
        } else {
            CsEventBus.getDefault().post(new NoConnectionEvent(NoConnectionEvent.LOAD_LIST));
        }
    }

    private String getTags() {
        return InAppStoryManager.getInstance().getTagsString();
    }

    private String getTestKey() {
        return InAppStoryManager.getInstance().getTestKey();
    }


    Handler timerHandler = new Handler();
    public long timerStart;
    public long timerDuration;
    public long totalTimerDuration;
    public long pauseShift;

    Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - timerStart >= timerDuration) {
                timerHandler.removeCallbacks(timerTask);
                pauseShift = 0;
                CsEventBus.getDefault().post(new NextStoryPageEvent(currentId));
                return;
                //if (currentIndex == )
            }
            timerHandler.postDelayed(timerTask, 50);
        }
    };

    public void startTimer(long timerDuration, boolean clearDuration) {
        Log.e("startTimer", timerDuration + "");
        if (timerDuration == 0) {
            try {
                timerHandler.removeCallbacks(timerTask);
                this.timerDuration = timerDuration;
                if (clearDuration)
                    this.totalTimerDuration = timerDuration;
            } catch (Exception e) {

            }
            return;
        }
        if (timerDuration < 0) {
            return;
        }
        pauseShift = 0;
        timerStart = System.currentTimeMillis();
        this.timerDuration = timerDuration;
        try {
            timerHandler.removeCallbacks(timerTask);
        } catch (Exception e) {

        }
        timerHandler.post(timerTask);
    }

    public void restartTimer(long duration) {
        startTimer(duration, true);
    }

    @CsSubscribe
    public void storyPageTapEvent(StoryReaderTapEvent event) {
        if (event.getLink() != null && !event.getLink().isEmpty()) {
            StoryLinkObject object = JsonParser.fromJson(event.getLink(), StoryLinkObject.class);// new Gson().fromJson(event.getLink(), StoryLinkObject.class);
            if (object != null) {
                switch (object.getLink().getType()) {
                    case "url":
                        Story story = StoryDownloader.getInstance().getStoryById(currentId);
                        CsEventBus.getDefault().post(new ClickOnButton(story.id, story.title,
                                story.tags, story.slidesCount, story.lastIndex,
                                object.getLink().getTarget()));

                        OldStatisticManager.getInstance().addLinkOpenStatistic();
                        if (InAppStoryManager.getInstance().getUrlClickCallback() != null) {
                            InAppStoryManager.getInstance().getUrlClickCallback().onUrlClick(
                                    object.getLink().getTarget()
                            );
                        } else {
                            if (!isConnected()) {
                                return;
                            }
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            i.setData(Uri.parse(object.getLink().getTarget()));
                            startActivity(i);
                        }
                        break;
                    default:
                        if (InAppStoryManager.getInstance().getAppClickCallback() != null) {
                            InAppStoryManager.getInstance().getAppClickCallback().onAppClick(
                                    object.getLink().getType(),
                                    object.getLink().getTarget()
                            );
                        }
                        break;
                }
            }
        }
    }

    public List<Story> favStories = new ArrayList<>();
    public List<FavoriteImage> favoriteImages = new ArrayList<>();

    void setLocalsOpened(List<Story> response) {
        Set<String> opens = SharedPreferencesAPI.getStringSet(InAppStoryManager.getInstance().getLocalOpensKey());
        if (opens == null) opens = new HashSet<>();
        for (Story story : response) {
            if (story.isOpened) {
                opens.add(Integer.toString(story.id));
            } else if (opens.contains(Integer.toString(story.id))) {
                story.isOpened = true;
            }
        }
        SharedPreferencesAPI.saveStringSet(InAppStoryManager.getInstance().getLocalOpensKey(), opens);
    }

    NetworkCallback loadCallback = new NetworkCallback<List<Story>>() {

        @Override
        public Type getType() {
            ParameterizedType ptype = new ParameterizedType() {
                @NonNull
                @Override
                public Type[] getActualTypeArguments() {
                    return new Type[]{Story.class};
                }

                @NonNull
                @Override
                public Type getRawType() {
                    return List.class;
                }

                @Nullable
                @Override
                public Type getOwnerType() {
                    return List.class;
                }
            };
            return ptype;
        }

        boolean isRefreshing = false;

        @Override
        public void onError(int code, String message) {

            CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
            super.onError(code, message);
        }

        @Override
        protected void error424(String message) {
            CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
            SessionManager.getInstance().openSession(new OpenSessionCallback() {
                @Override
                public void onSuccess() {
                    if (!isRefreshing)
                        isRefreshing = true;

                    NetworkClient.getApi().getStories(StatisticSession.getInstance().id, getTags(), getTestKey(),
                            null).enqueue(loadCallback);
                }

                @Override
                public void onError() {

                    CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
                }
            });

        }

        @Override
        public void onSuccess(final List<Story> response) {
            ArrayList<Story> stories = new ArrayList<>();
            for (int i = 0; i < Math.min(response.size(), 4); i++) {
                stories.add(response.get(i));
            }
            try {
                SharedPreferencesAPI.saveString("widgetStories", JsonParser.getJson(stories));
            } catch (Exception e) {
                e.printStackTrace();
            }
            CsEventBus.getDefault().post(new ListLoadedEvent());
            if (response == null || response.size() == 0) {
                if (AppearanceManager.csWidgetAppearance() != null && AppearanceManager.csWidgetAppearance().widgetClass != null) {
                    StoriesWidgetService.loadEmpty(getApplicationContext(), AppearanceManager.csWidgetAppearance().widgetClass);
                }
                CsEventBus.getDefault().post(new ContentLoadedEvent(true));
            } else {
                if (AppearanceManager.csWidgetAppearance() != null && AppearanceManager.csWidgetAppearance().widgetClass != null) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            StoriesWidgetService.loadSuccess(getApplicationContext(), AppearanceManager.csWidgetAppearance().widgetClass);
                        }
                    }, 500);
                }

                CsEventBus.getDefault().post(new ContentLoadedEvent(false));
            }
            setLocalsOpened(response);
            StoryDownloader.getInstance().uploadingAdditional(response);
            CsEventBus.getDefault().post(new ListVisibilityEvent());
            List<Story> newStories = new ArrayList<>();
            if (StoryDownloader.getInstance().getStories() != null) {
                for (Story story : response) {
                    if (!StoryDownloader.getInstance().getStories().contains(story)) {
                        newStories.add(story);
                    }
                }
            }
            if (newStories != null && newStories.size() > 0) {
                try {
                    StoryDownloader.getInstance().uploadingAdditional(newStories);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (InAppStoryManager.getInstance().hasFavorite) {
                NetworkClient.getApi().getStories(StatisticSession.getInstance().id, getTestKey(), 1,
                        null, "id, background_color, image",
                        null).enqueue(new NetworkCallback<List<Story>>() {
                    @Override
                    public void onSuccess(List<Story> response2) {
                        favStories.clear();
                        favStories.addAll(response2);
                        favoriteImages.clear();
                        CsEventBus.getDefault().post(new LoadFavStories());
                        if (response2 != null && response2.size() > 0) {
                            Set<String> opens = SharedPreferencesAPI.getStringSet(InAppStoryManager.getInstance().getLocalOpensKey());
                            if (opens == null) opens = new HashSet<>();
                            for (Story story : response2) {
                                //if (favoriteImages.size() < 4)
                                favoriteImages.add(new FavoriteImage(story.id, story.image, story.backgroundColor));
                                opens.add(Integer.toString(story.id));
                            }
                            SharedPreferencesAPI.saveStringSet(InAppStoryManager.getInstance().getLocalOpensKey(), opens);
                            if (loadStoriesCallback != null) {
                                List<Integer> ids = new ArrayList<>();
                                for (Story story : response) {
                                    ids.add(story.id);
                                }
                                loadStoriesCallback.storiesLoaded(ids);
                            }

                        } else {
                            if (loadStoriesCallback != null) {
                                List<Integer> ids = new ArrayList<>();
                                for (Story story : response) {
                                    ids.add(story.id);
                                }
                                loadStoriesCallback.storiesLoaded(ids);
                            }
                        }
                    }

                    @Override
                    public Type getType() {
                        ParameterizedType ptype = new ParameterizedType() {
                            @NonNull
                            @Override
                            public Type[] getActualTypeArguments() {
                                return new Type[]{Story.class};
                            }

                            @NonNull
                            @Override
                            public Type getRawType() {
                                return List.class;
                            }

                            @Nullable
                            @Override
                            public Type getOwnerType() {
                                return List.class;
                            }
                        };
                        return ptype;
                    }

                    @Override
                    public void onError(int code, String m) {
                        if (loadStoriesCallback != null) {
                            List<Integer> ids = new ArrayList<>();
                            for (Story story : response) {
                                ids.add(story.id);
                            }
                            loadStoriesCallback.storiesLoaded(ids);
                        }
                    }
                });
            } else {
                if (loadStoriesCallback != null) {
                    List<Integer> ids = new ArrayList<>();
                    for (Story story : response) {
                        ids.add(story.id);
                    }
                    loadStoriesCallback.storiesLoaded(ids);
                }
            }
        }
    };


    NetworkCallback loadCallbackWithoutFav = new NetworkCallback<List<Story>>() {

        @Override
        public Type getType() {
            ParameterizedType ptype = new ParameterizedType() {
                @NonNull
                @Override
                public Type[] getActualTypeArguments() {
                    return new Type[]{Story.class};
                }

                @NonNull
                @Override
                public Type getRawType() {
                    return List.class;
                }

                @Nullable
                @Override
                public Type getOwnerType() {
                    return List.class;
                }
            };
            return ptype;
        }

        boolean isRefreshing = false;

        @Override
        public void onError(int code, String message) {

            CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
            super.onError(code, message);
        }

        @Override
        protected void error424(String message) {
            CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
            SessionManager.getInstance().openSession(new OpenSessionCallback() {
                @Override
                public void onSuccess() {
                    if (!isRefreshing)
                        isRefreshing = true;

                    NetworkClient.getApi().getStories(StatisticSession.getInstance().id, getTags(), getTestKey(),
                            null).enqueue(loadCallbackWithoutFav);
                }

                @Override
                public void onError() {

                    CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_LIST));
                }
            });

        }

        @Override
        public void onSuccess(final List<Story> response) {
            if (response == null || response.size() == 0) {
                CsEventBus.getDefault().post(new ContentLoadedEvent(true));
            } else {
                CsEventBus.getDefault().post(new ContentLoadedEvent(false));
            }
            StoryDownloader.getInstance().uploadingAdditional(response);
            CsEventBus.getDefault().post(new ListVisibilityEvent());
            List<Story> newStories = new ArrayList<>();
            if (StoryDownloader.getInstance().getStories() != null) {
                for (Story story : response) {
                    if (!StoryDownloader.getInstance().getStories().contains(story)) {
                        newStories.add(story);
                    }
                }
            }
            if (newStories != null && newStories.size() > 0) {
                try {
                    StoryDownloader.getInstance().uploadingAdditional(newStories);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (loadStoriesCallback != null) {
                List<Integer> ids = new ArrayList<>();
                for (Story story : response) {
                    ids.add(story.id);
                }
                loadStoriesCallback.storiesLoaded(ids);
            }
        }
    };

    private int currentId;

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    private int currentIndex;

    public long lastTapEventTime = 0;
    public boolean cubeAnimation = false;

    @CsSubscribe
    public void nextStoryEvent(NextStoryReaderEvent event) {
        lastTapEventTime = System.currentTimeMillis() + 100;
        cubeAnimation = true;
    }


    @CsSubscribe
    public void prevStoryEvent(PrevStoryReaderEvent event) {
        lastTapEventTime = System.currentTimeMillis() + 100;
        cubeAnimation = true;
    }


    @Override
    public void onDestroy() {
        Log.e("InAppService", "destroy");
        super.onDestroy();
    }

   /* public class StatisticEvent {
        public int eventType;
        public int storyId;
        public int index;
        public long timer;

        public StatisticEvent(int eventType, int storyId, int index) {
            this.eventType = eventType;
            this.storyId = storyId;
            this.index = index;
            this.timer = System.currentTimeMillis();
        }

        public StatisticEvent(int eventType, int storyId, int index, long timer) {
            this.eventType = eventType;
            this.storyId = storyId;
            this.index = index;
            this.timer = timer;
        }
    }

    public StatisticEvent currentEvent;

    private void addStatisticEvent(int eventType, int storyId, int index) {
        currentEvent = new StatisticEvent(eventType, storyId, index);
    }

    public void closeStatisticEvent() {
        closeStatisticEvent(null, false);
    }

    private void addArticleStatisticEvent(int eventType, int articleId) {
        currentEvent = new StatisticEvent(eventType, articleEventCount, articleId, articleTimer);
    }


    public int eventCount = 0;

    public void addStatisticBlock(int storyId, int index) {
        //if (currentEvent != null)
        closeStatisticEvent();
        addStatisticEvent(1, storyId, index);
        eventCount++;
    }

    public int articleEventCount = 0;
    public long articleTimer = 0;

    public void addArticleOpenStatistic(int eventType, int articleId) {
        articleEventCount = eventCount;
        currentEvent.eventType = 2;
        closeStatisticEvent();
        eventCount++;
        articleTimer = System.currentTimeMillis();
        addArticleStatisticEvent(eventType, articleId);
    }

    public void addLinkOpenStatistic() {
        currentEvent.eventType = 2;
    }

    public void closeStatisticEvent(final Integer time, boolean clear) {
        if (currentEvent != null) {
            putStatistic(new ArrayList<Object>() {{
                add(currentEvent.eventType);
                add(eventCount);
                add(currentEvent.storyId);
                add(currentEvent.index);
                add(Math.max(time != null ? time : System.currentTimeMillis() - currentEvent.timer, 0));
            }});
            Log.e("statisticEvent", currentEvent.eventType + " " + eventCount + " " +
                    currentEvent.storyId + " " + currentEvent.index + " " +
                    Math.max(time != null ? time : System.currentTimeMillis() - currentEvent.timer, 0));
            if (!clear)
                currentEvent = null;
        }
    }

    public void addDeeplinkClickStatistic(int id) {
        closeStatisticEvent();
        eventCount++;
        addStatisticEvent(1, id, 0);
        closeStatisticEvent(0, false);
        eventCount++;
        addStatisticEvent(2, id, 0);
        closeStatisticEvent(0, false);
    }

    public void addArticleCloseStatistic() {
        closeStatisticEvent();
        eventCount++;
        addStatisticEvent(1, currentId, currentIndex);
    }*/

    public void resumeTimer() {
        Log.e("startTimer", "resumeTimer");
        StatisticManager.getInstance().cleanFakeEvents();
        resumeLocalTimer();
        if (OldStatisticManager.getInstance().currentEvent == null) return;
        OldStatisticManager.getInstance().currentEvent.eventType = 1;
        OldStatisticManager.getInstance().currentEvent.timer = System.currentTimeMillis();
        pauseTime += System.currentTimeMillis() - startPauseTime;
        StatisticManager.getInstance().currentState.storyPause = pauseTime;
        startPauseTime = 0;
    }

    public void resumeLocalTimer() {
        Log.e("dragDrop", System.currentTimeMillis() + " " + timerDuration + " " + pauseShift);
        startTimer(timerDuration - pauseShift, false);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
       /* Log.e("onTaskRemoved", ""+currentId);
        if (currentId != 0) {
            Story story = StoryDownloader.getInstance().getStoryById(currentId);
            pauseTime += System.currentTimeMillis() - startPauseTime;
            currentState.storyPause = pauseTime;
            Log.e("pauseTime", "" + pauseTime);
            StatisticManager.getInstance().sendCloseStory(story.id, StatisticManager.APPCLOSE, story.lastIndex, story.slidesCount, pauseTime);
        }*/
    }

    public long startPauseTime;


    public long pauseTime = 0;

    @CsSubscribe(threadMode = CsThreadMode.MAIN)
    public void changeStoryPageEvent(StoryPageOpenEvent event) {
        OldStatisticManager.getInstance().addStatisticBlock(event.storyId, event.index);
        StatisticManager.getInstance().createCurrentState(event.storyId, event.index);
    }

    public void pauseLocalTimer() {
        try {
            timerHandler.removeCallbacks(timerTask);
        } catch (Exception e) {

        }
        pauseShift = (System.currentTimeMillis() - timerStart);
    }





    public void pauseTimer() {
        Story story = StoryDownloader.getInstance().getStoryById(currentId);

        StatisticManager.getInstance().addFakeEvents(story.id, story.lastIndex, story.slidesCount);
        pauseLocalTimer();
        startPauseTime = System.currentTimeMillis();
        OldStatisticManager.getInstance().closeStatisticEvent(null, true);
        OldStatisticManager.getInstance().sendStatistic();
        OldStatisticManager.getInstance().eventCount++;
    }



    public boolean isBackgroundPause = false;


    @CsSubscribe
    public void destroyFragmentEvent(DestroyStoriesFragmentEvent event) {
        currentId = 0;
        currentIndex = 0;
        for (int i = 0; i < StoryDownloader.getInstance().getStories().size(); i++) {
            StoryDownloader.getInstance().getStories().get(i).lastIndex = 0;
        }
    }

    public void changeOuterIndex(int storyIndex) {

    }


    @CsSubscribe
    public void pauseStoryEvent(PauseStoryReaderEvent event) {
        try {
            if (event.isWithBackground()) {
                isBackgroundPause = true;
                pauseTimer();
            } else {
                pauseLocalTimer();
            }
        } catch (Exception e) {

        }
    }

    boolean backPaused = false;

    @CsSubscribe
    public void resumeStoryEvent(ResumeStoryReaderEvent event) {
        if (event.isWithBackground()) {
            isBackgroundPause = false;
            resumeTimer();
        } else {
            resumeLocalTimer();
        }
    }


    public static boolean isConnected() {
        if (InAppStoryManager.getInstance() == null) return false;
        if (InAppStoryManager.getInstance().context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) InAppStoryManager.getInstance().context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            return (info != null && info.isConnected());
        } catch (Exception e) {
            return true;
        }
    }

    public void saveSesstionPlaceholders(List<StoryPlaceholder> placeholders) {
        if (placeholders == null) return;
        for (StoryPlaceholder placeholder : placeholders) {
            String key = "%" + placeholder.name + "%";
            InAppStoryManager.getInstance().defaultPlaceholders.put(key,
                    placeholder.defaultVal);
            if (!InAppStoryManager.getInstance().placeholders.containsKey(key)) {
                InAppStoryManager.getInstance().placeholders.put(key,
                        placeholder.defaultVal);
            }
        }
    }

    public void downloadFonts(List<CacheFontObject> cachedFonts) {
        if (cachedFonts != null) {
            for (CacheFontObject cacheFontObject : cachedFonts) {
                Downloader.downFontFile(InAppStoryManager.getInstance().context, cacheFontObject.url);
            }
        }
    }

    public void runStatisticThread() {

        if (handler != null)
            handler.postDelayed(OldStatisticManager.getInstance().statisticUpdateThread, statisticUpdateInterval);
    }

    private static final long statisticUpdateInterval = 30000;

    private Handler handler = new Handler();

  /*  public void putStatistic(List<Object> e) {
        if (statistic != null) {
            statistic.add(e);
        }
    }

    List<List<Object>> statistic = new ArrayList<>();

    public Runnable statisticUpdateThread = new Runnable() {
        @Override
        public void run() {
            if (InAppStoryManager.getInstance().context == null || getInstance() == null) {
                handler.removeCallbacks(statisticUpdateThread);
                return;
            }
            if (OldStatisticManager.getInstance().sendStatistic()) {
                handler.postDelayed(statisticUpdateThread, statisticUpdateInterval);
            }
        }
    };
*/
    /*public void previewStatisticEvent(ArrayList<Integer> vals) {
        ArrayList<Object> sendObject = new ArrayList<Object>() {{
            add(5);
            add(eventCount);
        }};
        ArrayList<Integer> addedVals = new ArrayList<>();
        for (Integer val : vals) {
            if (!StatisticSession.getInstance().viewed.contains(val)) {
                sendObject.add(val);
                StatisticSession.getInstance().viewed.add(val);
            }
        }
        if (sendObject.size() > 2) {
            putStatistic(sendObject);
            eventCount++;
        }

    }

    public boolean sendStatistic() {
        if (!isConnected()) return true;
        if (StatisticSession.needToUpdate())
            return false;
        if (statistic == null || (statistic.isEmpty() && !StatisticSession.needToUpdate())) {
            return true;
        }
        if (!InAppStoryManager.getInstance().sendStatistic) {
            StatisticSession.getInstance();
            StatisticSession.updateStatistic();
            if (statistic != null)
                statistic.clear();
            return true;
        }
        try {
            NetworkClient.getApi().statisticsUpdate(
                    new StatisticSendObject(StatisticSession.getInstance().id,
                            statistic)).enqueue(new NetworkCallback<StatisticResponse>() {
                @Override
                public void onSuccess(StatisticResponse response) {
                    StatisticSession.getInstance();
                    StatisticSession.updateStatistic();
                    if (statistic == null) return;
                    statistic.clear();
                }

                @Override
                public Type getType() {
                    return StatisticResponse.class;
                }
            });
        } catch (Exception e) {
        }
        return true;
    }

*/



    public void getStoryById(final GetStoryByIdCallback storyByIdCallback, final int id) {
        for (Story story : StoryDownloader.getInstance().getStories()) {
            if (story.id == id) {
                storyByIdCallback.getStory(story);
                return;
            }
        }
    }

    public interface LikeDislikeCallback {
        void onSuccess();

        void onError();
    }

    public void likeDislikeClick(boolean isDislike, final int storyId,
                                 final LikeDislikeCallback callback) {
        final Story story = StoryDownloader.getInstance().findItemByStoryId(storyId);
        final int val;
        if (isDislike) {
            if (story.disliked()) {
                CsEventBus.getDefault().post(new DislikeStory(story.id, story.title,
                        story.tags, story.slidesCount, story.lastIndex, false));
                //  StatisticSendManager.getInstance().sendDislikeStory(story.id, story.lastIndex);
                val = 0;
            } else {
                CsEventBus.getDefault().post(new DislikeStory(story.id, story.title,
                        story.tags, story.slidesCount, story.lastIndex, true));
                StatisticManager.getInstance().sendDislikeStory(story.id, story.lastIndex);
                val = -1;
            }
        } else {
            if (story.liked()) {
                CsEventBus.getDefault().post(new LikeStory(story.id, story.title,
                        story.tags, story.slidesCount, story.lastIndex, false));
                //  StatisticSendManager.getInstance().sendLikeStory(story.id, story.lastIndex);
                val = 0;
            } else {
                CsEventBus.getDefault().post(new LikeStory(story.id, story.title,
                        story.tags, story.slidesCount, story.lastIndex, true));
                StatisticManager.getInstance().sendLikeStory(story.id, story.lastIndex);
                val = 1;
            }
        }


        NetworkClient.getApi().storyLike(Integer.toString(storyId),
                StatisticSession.getInstance().id,
                getApiKey(), val).enqueue(
                new NetworkCallback<Response>() {
                    @Override
                    public void onSuccess(Response response) {
                        if (story != null)
                            story.like = val;
                        callback.onSuccess();
                        CsEventBus.getDefault().post(new LikeDislikeEvent(storyId, val));
                    }


                    @Override
                    public void onError(int code, String message) {
                        super.onError(code, message);
                        callback.onError();
                    }

                    @Override
                    public Type getType() {
                        return null;
                    }
                });
    }

    public void favoriteClick(final int storyId, final LikeDislikeCallback callback) {
        final Story story = StoryDownloader.getInstance().findItemByStoryId(storyId);
        final boolean val = story.favorite;
        if (!story.favorite)
            StatisticManager.getInstance().sendFavoriteStory(story.id, story.lastIndex);
        CsEventBus.getDefault().post(new FavoriteStory(story.id, story.title,
                story.tags, story.slidesCount, story.lastIndex, !story.favorite));
        NetworkClient.getApi().storyFavorite(Integer.toString(storyId),
                StatisticSession.getInstance().id,
                getApiKey(), val ? 0 : 1).enqueue(
                new NetworkCallback<Response>() {
                    @Override
                    public void onSuccess(Response response) {
                        if (story != null)
                            story.favorite = !val;
                        callback.onSuccess();
                        CsEventBus.getDefault().post(new StoryFavoriteEvent(storyId, !val));
                    }

                    @Override
                    public void onError(int code, String message) {
                        super.onError(code, message);
                        callback.onError();
                    }

                    @Override
                    public Type getType() {
                        return null;
                    }
                });

    }

    public void getFullStoryById(final GetStoryByIdCallback storyByIdCallback, final int id) {
        for (Story story : StoryDownloader.getInstance().getStories()) {
            if (story.id == id) {
                if (story.pages != null) {
                    storyByIdCallback.getStory(story);
                    return;
                } else {
                    storyByIdCallback.getStory(story);
                    return;
                }
            }
        }
    }

    public void getFullStoryByStringId(final GetStoryByIdCallback storyByIdCallback, final String id) {
        SessionManager.getInstance().useOrOpenSession(new OpenSessionCallback() {
            @Override
            public void onSuccess() {

                NetworkClient.getApi().getStoryById(id, StatisticSession.getInstance().id, 1,
                        getApiKey(), EXPAND_STRING
                ).enqueue(new NetworkCallback<Story>() {
                    @Override
                    public void onSuccess(final Story response) {
                        CsEventBus.getDefault().post(new SingleLoad());
                        if (InAppStoryManager.getInstance().singleLoadedListener != null) {
                            InAppStoryManager.getInstance().singleLoadedListener.onLoad();
                        }
                        StoryDownloader.getInstance().uploadingAdditional(new ArrayList<Story>() {{
                            add(response);
                        }});
                        StoryDownloader.getInstance().setStory(response, response.id);
                        storyByIdCallback.getStory(response);
                    }

                    @Override
                    public Type getType() {
                        return Story.class;
                    }

                    @Override
                    public void onError(int code, String message) {
                        if (InAppStoryManager.getInstance().singleLoadedListener != null) {
                            InAppStoryManager.getInstance().singleLoadedListener.onError();
                        }
                        CsEventBus.getDefault().post(new SingleLoadError());
                        CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_SINGLE));
                    }
                });
            }

            @Override
            public void onError() {
                CsEventBus.getDefault().post(new StoriesErrorEvent(StoriesErrorEvent.LOAD_SINGLE));
            }

        });
    }


    public static final String EXPAND_STRING = "slides_html,layout,slides_duration,src_list";

    private String getApiKey() {
        return InAppStoryManager.getInstance().getApiKey();
    }


    private static final String CRASH_KEY = "CRASH_KEY";

    public class TryMe implements Thread.UncaughtExceptionHandler {

        Thread.UncaughtExceptionHandler oldHandler;

        public TryMe() {
            oldHandler = Thread.getDefaultUncaughtExceptionHandler(); // сохраним ранее установленный обработчик
        }

        @Override
        public void uncaughtException(Thread thread, final Throwable throwable) {
            SharedPreferencesAPI.saveString(CRASH_KEY, throwable.getCause().toString() + "\n" + throwable.getMessage());
            System.exit(0);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("ias_network", "onCreate");
        CsEventBus.getDefault().register(this);

        Thread.setDefaultUncaughtExceptionHandler(new TryMe());
        ImageLoader imgLoader = new ImageLoader(getApplicationContext());
        OldStatisticManager.getInstance().statistic = new ArrayList<>();
        INSTANCE = this;

    }

    public void startForegr() {
        if (Build.VERSION.SDK_INT >= 26) {
            final NotificationManager manager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Stories service");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("123")
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setContentText("456")
                    .setChannelId(CHANNEL_ID)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true).build();

            startForeground(NOTIFICATION_ID, notification);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    manager.cancel(NOTIFICATION_ID);
                }
            }, 200);
        }
    }

    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        INSTANCE = this;
    }


    public static final String CHANNEL_ID = "inAppStorySdk";
    private static final String CHANNEL_NAME = "inAppStorySdk";
    private static final int NOTIFICATION_ID = 791;

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {

        Log.e("ias_network", "onStartCommand");
        INSTANCE = this;
        return START_STICKY;
    }

    public int getCurrentId() {
        return currentId;
    }

    public void setCurrentId(int currentId) {
        this.currentId = currentId;
    }
}
