package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.RatingDao;
import com.cappielloantonio.tempo.interfaces.RatingCallback;
import com.cappielloantonio.tempo.model.Rating;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.util.NetworkUtil;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Ratings, with the same deferred delivery favourites have: one that cannot be
 * sent now is parked in a table and replayed the next time the home screen is
 * built. Album and artist ratings share this class because setRating takes a
 * plain id — the three identical copies that used to sit on SongRepository,
 * AlbumRepository and ArtistRepository were folded into it.
 */
@OptIn(markerClass = UnstableApi.class)
public class RatingRepository {
    private final RatingDao ratingDao = AppDatabase.getInstance().ratingDao();

    /**
     * Rates offline-tolerantly: with no connection the rating goes straight to
     * the queue, and a request that fails anyway lands there too.
     */
    public void rate(String id, int rating) {
        if (NetworkUtil.isOffline()) {
            rateLater(id, rating);
            return;
        }

        rate(id, rating, new RatingCallback() {
            @Override
            public void onError() {
                rateLater(id, rating);
            }
        });
    }

    public void rate(String id, int rating, RatingCallback ratingCallback) {
        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .setRating(id, rating)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()) {
                            ratingCallback.onSuccess();
                        } else {
                            ratingCallback.onError();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        ratingCallback.onError();
                    }
                });
    }

    public void rateLater(String id, int rating) {
        InsertThreadSafe insert = new InsertThreadSafe(ratingDao, new Rating(System.currentTimeMillis(), id, rating));
        Thread thread = new Thread(insert);
        thread.start();
    }

    public List<Rating> getRatings() {
        List<Rating> ratings = new ArrayList<>();

        GetAllThreadSafe getAllThreadSafe = new GetAllThreadSafe(ratingDao);
        Thread thread = new Thread(getAllThreadSafe);
        thread.start();

        try {
            thread.join();
            ratings = getAllThreadSafe.getRatings();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return ratings;
    }

    public void delete(Rating rating) {
        DeleteThreadSafe delete = new DeleteThreadSafe(ratingDao, rating);
        Thread thread = new Thread(delete);
        thread.start();
    }

    private static class GetAllThreadSafe implements Runnable {
        private final RatingDao ratingDao;
        private List<Rating> ratings = new ArrayList<>();

        public GetAllThreadSafe(RatingDao ratingDao) {
            this.ratingDao = ratingDao;
        }

        @Override
        public void run() {
            ratings = ratingDao.getAll();
        }

        public List<Rating> getRatings() {
            return ratings;
        }
    }

    private static class InsertThreadSafe implements Runnable {
        private final RatingDao ratingDao;
        private final Rating rating;

        public InsertThreadSafe(RatingDao ratingDao, Rating rating) {
            this.ratingDao = ratingDao;
            this.rating = rating;
        }

        @Override
        public void run() {
            ratingDao.insert(rating);
        }
    }

    private static class DeleteThreadSafe implements Runnable {
        private final RatingDao ratingDao;
        private final Rating rating;

        public DeleteThreadSafe(RatingDao ratingDao, Rating rating) {
            this.ratingDao = ratingDao;
            this.rating = rating;
        }

        @Override
        public void run() {
            ratingDao.delete(rating);
        }
    }
}
