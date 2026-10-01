package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Directory;
import com.cappielloantonio.tempo.subsonic.models.Indexes;
import com.cappielloantonio.tempo.subsonic.models.MusicFolder;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DirectoryRepository {
    private static final String TAG = "DirectoryRepository";

    public MutableLiveData<List<MusicFolder>> getMusicFolders() {
        MutableLiveData<List<MusicFolder>> liveMusicFolders = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getMusicFolders()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getMusicFolders() != null) {
                            liveMusicFolders.setValue(response.body().getSubsonicResponse().getMusicFolders().getMusicFolders());
                        } else {
                            liveMusicFolders.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        // null is a failed request, so a loading placeholder knows to stop waiting.
                        liveMusicFolders.setValue(null);
                    }
                });

        return liveMusicFolders;
    }

    public MutableLiveData<Indexes> getIndexes(String musicFolderId, Long ifModifiedSince) {
        MutableLiveData<Indexes> liveIndexes = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getIndexes(musicFolderId, ifModifiedSince)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getIndexes() != null) {
                            liveIndexes.setValue(response.body().getSubsonicResponse().getIndexes());
                        } else {
                            liveIndexes.setValue(null);
                        }
                    }

                    // null is a failed request; no value yet, one still on its way.
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        liveIndexes.setValue(null);
                    }
                });

        return liveIndexes;
    }

    public MutableLiveData<Directory> getMusicDirectory(String id) {
        MutableLiveData<Directory> liveMusicDirectory = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getMusicDirectory(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getDirectory() != null) {
                            liveMusicDirectory.setValue(response.body().getSubsonicResponse().getDirectory());
                        } else {
                            liveMusicDirectory.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        liveMusicDirectory.setValue(null);
                    }
                });

        return liveMusicDirectory;
    }
}
