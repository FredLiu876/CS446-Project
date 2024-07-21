package com.example.watpool.ui.maps

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watpool.services.FirebaseService
import com.example.watpool.services.models.Coordinate
import com.example.watpool.services.models.Postings
import com.example.watpool.services.models.Trips
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
class MapsViewModel: ViewModel() {
    private val _postingsInLocation : MutableLiveData<MutableList<Postings>> = MutableLiveData(
        mutableListOf())
    val postingsInRadius: LiveData<MutableList<Postings>> get() = _postingsInLocation

    fun fetchPostingsByStartAndEnd(firebaseService: FirebaseService, latitudeStart:Double, longitudeStart: Double, radiusInKmStart: Double, latitudeEnd: Double, longitudeEnd: Double, radiusInKmEnd: Double){
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val postingsSnapshot = firebaseService.fetchTripsByStartAndEnd(
                    latitudeStart, longitudeStart, radiusInKmStart,
                    latitudeEnd, longitudeEnd, radiusInKmEnd
                ).await()

                val postingList = mutableListOf<Postings>()
                val deferredTasks = postingsSnapshot.map{ document ->
                    async {
                        val dataModel = document.toObject(Postings::class.java)
                        if (dataModel != null) {
                            val startCoordTask = async {
                                val startSnapshot = firebaseService.fetchCoordinatesByDocumentId(dataModel.startingCoordinateId).await()
                                startSnapshot.toObject(Coordinate::class.java)
                            }
                            val endCoordTask = async {
                                val endSnapshot = firebaseService.fetchCoordinatesByDocumentId(dataModel.endingCoordinateId).await()
                                endSnapshot.toObject(Coordinate::class.java)
                            }

                            val startCoord = startCoordTask.await()
                            val endCoord = endCoordTask.await()

                            if (startCoord != null && endCoord != null) {
                                dataModel.startCoords = startCoord
                                dataModel.endCoords = endCoord
                                synchronized(postingList) {
                                    postingList.add(dataModel)
                                }
                            }
                        }
                    }
                }

                deferredTasks.forEach { it.await() }
                _postingsInLocation.postValue(postingList)
            } catch (e: Exception) {
                Log.e("MapsViewModel", "Unable to fetch postings: ${e.message}", e)
            }
        }

        /* viewModelScope.launch {
            val postingList : MutableList<Postings> = mutableListOf()

            val postings = firebaseService.fetchTripsByStartAndEnd(
                latitudeStart, longitudeStart, radiusInKmStart,
                latitudeEnd, longitudeEnd, radiusInKmEnd
            )
            postings.addOnSuccessListener { documentSnapshot ->
                val totalDocuments = documentSnapshot.size
                var processedDocuments = 0

                for (document in documentSnapshot) {
                    val dataModel = document.toObject(Postings::class.java)
                    if (dataModel != null) {
                        Log.e("Search", "Coordinate1 " + dataModel.endingCoordinateId)

                        val startData = firebaseService.fetchCoordinatesByDocumentId(dataModel.endingCoordinateId)
                        val endData = firebaseService.fetchCoordinatesByDocumentId(dataModel.endingCoordinateId)

                        startData.addOnCompleteListener() { snap ->
                            val startCoordData = snap.result.toObject(Coordinate::class.java)
                            if (startCoordData != null) {
                                dataModel.startCoords = startCoordData
                            }

                        }.addOnFailureListener {
                            Log.e("Search", "Error fetching start coordinates: ", it)
                        }

                        endData.addOnCompleteListener() { snap ->
                            val endCoordData = snap.result.toObject(Coordinate::class.java)
                            if (endCoordData != null) {
                                dataModel.endCoords = endCoordData
                            }
                        }.addOnFailureListener {
                            Log.e("Search", "Error fetching end coordinates: ", it)
                        }
                        postingList.add(dataModel)
                    }
                }
                _postingsInLocation.value = postingList
            }.addOnFailureListener {
                Log.e("MapsViewModel", "Unable To Fetch Postings: " + it.message)
            }
        }*/
    }



}