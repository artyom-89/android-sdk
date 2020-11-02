package io.casestory.sdk.stories.ui.list;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.casestory.casestorysdk.R;
import io.casestory.sdk.AppearanceManager;
import io.casestory.sdk.CaseStoryManager;
import io.casestory.sdk.CaseStoryService;
import io.casestory.sdk.eventbus.CsEventBus;
import io.casestory.sdk.eventbus.CsSubscribe;
import io.casestory.sdk.eventbus.ThreadMode;
import io.casestory.sdk.exceptions.DataException;
import io.casestory.sdk.stories.api.models.Story;
import io.casestory.sdk.stories.api.models.callbacks.LoadStoriesCallback;
import io.casestory.sdk.stories.cache.StoryDownloader;
import io.casestory.sdk.stories.events.ChangeStoryEvent;
import io.casestory.sdk.stories.events.ChangeUserIdForListEvent;
import io.casestory.sdk.stories.events.CloseStoryReaderEvent;
import io.casestory.sdk.stories.events.OpenStoriesScreenEvent;
import io.casestory.sdk.stories.events.OpenStoryByIdEvent;
import io.casestory.sdk.stories.serviceevents.StoryFavoriteEvent;
import io.casestory.sdk.stories.utils.Sizes;

public class StoriesList extends RecyclerView {
    public StoriesList(@NonNull Context context) {
        super(context);
        init(null);
    }

    boolean isFavoriteList = false;

    public StoriesList(@NonNull Context context, boolean isFavoriteList) {
        super(context);
        init(null);
        this.isFavoriteList = isFavoriteList;
    }


    public StoriesList(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public interface OnFavoriteItemClick {
        void onClick();
    }

    public StoriesList(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    @Override
    public void onDetachedFromWindow() {
        CsEventBus.getDefault().unregister(this);
        super.onDetachedFromWindow();
        Log.e("cslistEvent", "detached");
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        CsEventBus.getDefault().register(this);

        Log.e("cslistEvent", "attached");
    }

    private void init(AttributeSet attributeSet) {
        if (attributeSet != null) {
            TypedArray typedArray = getContext().obtainStyledAttributes(attributeSet, R.styleable.StoriesList);
            isFavoriteList = typedArray.getBoolean(R.styleable.StoriesList_cs_listIsFavorite, false);
            typedArray.recycle();
        }
        addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!readerIsOpened)
                    sendIndexes();

            }
        });
    }

    public void sendIndexes() {
        ArrayList<Integer> indexes = new ArrayList<>();
        if (layoutManager instanceof LinearLayoutManager) {
            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
            for (int i = linearLayoutManager.findFirstVisibleItemPosition();
                 i <= linearLayoutManager.findLastVisibleItemPosition(); i++) {
                if (adapter != null && adapter.getStoriesIds().size() > i && i >= 0)
                    indexes.add(adapter.getStoriesIds().get(i));
            }
        } else if (layoutManager instanceof GridLayoutManager) {
            GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
            for (int i = gridLayoutManager.findFirstVisibleItemPosition();
                 i <= gridLayoutManager.findLastVisibleItemPosition(); i++) {
                if (adapter != null && adapter.getStoriesIds().size() > i && i >= 0)
                    indexes.add(adapter.getStoriesIds().get(i));
            }
        }
        CaseStoryService.getInstance().previewStatisticEvent(indexes);
    }

    StoriesAdapter adapter;

    @Override
    public void setLayoutManager(LayoutManager layoutManager) {
        this.layoutManager = layoutManager;
        super.setLayoutManager(layoutManager);
    }

    LayoutManager layoutManager = new LinearLayoutManager(getContext(), HORIZONTAL, false);

    public void setAppearanceManager(AppearanceManager appearanceManager) {
        this.appearanceManager = appearanceManager;
    }

    public void setOnFavoriteItemClick(OnFavoriteItemClick favoriteItemClick) {
        this.favoriteItemClick = favoriteItemClick;
    }

    AppearanceManager appearanceManager;
    OnFavoriteItemClick favoriteItemClick;

    boolean readerIsOpened = false;

    @CsSubscribe
    public void openReaderEvent(OpenStoriesScreenEvent event) {
        readerIsOpened = true;
    }

    @CsSubscribe
    public void openReaderEvent(CloseStoryReaderEvent event) {
        readerIsOpened = false;
        sendIndexes();
    }

    @CsSubscribe
    public void openStoryByIdEvent(OpenStoryByIdEvent event) {
        StoryDownloader.getInstance().loadStories(StoryDownloader.getInstance().getStories(), event.getIndex());
    }

    @CsSubscribe(threadMode = ThreadMode.MAIN)
    public void changeUserId(ChangeUserIdForListEvent event) {
        try {
            adapter = null;
            loadStories();
        } catch (DataException e) {
            e.printStackTrace();
        }
    }


    @CsSubscribe(threadMode = ThreadMode.MAIN)
    public void changeStoryEvent(final ChangeStoryEvent event) {
        StoryDownloader.getInstance().getStoryById(event.getId()).isOpened = true;
        for (int i = 0; i < adapter.getStoriesIds().size(); i++) {
            if (adapter.getStoriesIds().get(i) == event.getId()) {
                adapter.notifyItemChanged(i);
                break;
            }
        }
        if (layoutManager instanceof LinearLayoutManager) {
            final int ind = adapter.getIndexById(event.getId());
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(ind > 0 ? ind : 0, 0);

            if (ind >= 0) {
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        int[] location = new int[2];
                        View v = layoutManager.findViewByPosition(ind);
                        if (v == null) return;
                        v.getLocationOnScreen(location);
                        int x = location[0];
                        int y = location[1];
                        CaseStoryManager.getInstance().coordinates = new Point(x + v.getWidth() / 2 - Sizes.dpToPxExt(8), y + v.getHeight() / 2);
                    }
                }, 950);
            }
        }
    }

    boolean hasFavItem;

    @CsSubscribe(threadMode = ThreadMode.MAIN)
    public void favItem(StoryFavoriteEvent event) {
        if (CaseStoryService.getInstance().favoriteImages == null)
            CaseStoryService.getInstance().favoriteImages = new ArrayList<>();
        List<FavoriteImage> favImages = CaseStoryService.getInstance().favoriteImages;
        boolean isEmpty = favImages.isEmpty();
        Story story = StoryDownloader.getInstance().getStoryById(event.getId());
        if (event.favStatus) {
            FavoriteImage favoriteImage = new FavoriteImage(Integer.valueOf(event.getId()), story.getImage(), story.getBackgroundColor());
            if (!favImages.contains(favoriteImage))
                favImages.add(0, favoriteImage);
        } else {
            for (FavoriteImage favoriteImage : favImages) {
                if (favoriteImage.getId() == Integer.valueOf(event.getId())) {
                    favImages.remove(favoriteImage);
                    break;
                }
            }
        }
        if (isFavoriteList) {
            adapter.hasFavItem = false;
            if (event.favStatus) {
                if (!adapter.getStoriesIds().contains(event.getId()))
                    adapter.getStoriesIds().add(event.getId());
            } else {
                if (adapter.getStoriesIds().contains(event.getId()))
                    adapter.getStoriesIds().remove(new Integer(event.getId()));
            }
            adapter.notifyDataSetChanged();
        } else if (isEmpty && !favImages.isEmpty()) {
            adapter.hasFavItem = (true && CaseStoryManager.getInstance().hasFavorite());
            // adapter.refresh();
            adapter.notifyDataSetChanged();
        } else if (!isEmpty && favImages.isEmpty()) {
            adapter.hasFavItem = false;
            adapter.notifyDataSetChanged();
            // adapter.refresh();
        } else {
            adapter.notifyItemChanged(getAdapter().getItemCount() - 1);
        }

    }

    public void loadStories() throws DataException {
        if (appearanceManager == null) {
            throw new DataException("Need to set an AppearanceManager", new Throwable("StoriesList data is not valid"));
        }
        if (CaseStoryManager.getInstance().getUserId() == null) {
            throw new DataException("'userId' can't be null", new Throwable("CaseStoryManager data is not valid"));
        }

        if (CaseStoryService.getInstance() != null) {
            CaseStoryService.getInstance().loadStories(new LoadStoriesCallback() {
                @Override
                public void storiesLoaded(List<Integer> storiesIds) {
                    if (adapter == null) {
                        adapter = new StoriesAdapter(getContext(), storiesIds, appearanceManager, favoriteItemClick, isFavoriteList);
                        setLayoutManager(layoutManager);
                        setAdapter(adapter);
                    } else {
                        adapter.refresh(storiesIds);
                    }
                }
            }, isFavoriteList);

        } else {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    CaseStoryService.getInstance().loadStories(new LoadStoriesCallback() {
                        @Override
                        public void storiesLoaded(List<Integer> storiesIds) {
                            adapter = new StoriesAdapter(getContext(), storiesIds, appearanceManager, favoriteItemClick, isFavoriteList);
                            setLayoutManager(layoutManager);
                            setAdapter(adapter);
                        }
                    }, isFavoriteList);
                }
            }, 1000);
        }

    }

}
