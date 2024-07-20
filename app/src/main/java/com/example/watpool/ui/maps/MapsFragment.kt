package com.example.watpool.ui.maps

import PlacesFragment
import PlacesViewModel
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.watpool.R
import com.example.watpool.databinding.FragmentMapsBinding
import com.example.watpool.services.FirebaseService
import com.example.watpool.services.models.Coordinate
import com.example.watpool.services.LocationService
import com.example.watpool.services.models.Trips
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.io.IOException
import java.util.Locale
import org.json.JSONObject


class MapsFragment : Fragment(), OnMapReadyCallback {

    // TODO: Create better map initialization to display user current location
    // TODO: Create button to recenter map on user location
    // TODO: Ability to create markers for user and other functionality

    // TODO: Cleanup map initilization and location usage

    private var _binding: FragmentMapsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    // Search view for maps
    private lateinit var searchView : SearchView

    private lateinit var placesFragment: PlacesFragment

    // View Models
    private val mapsViewModel: MapsViewModel by viewModels()
    private lateinit var placesViewModel: PlacesViewModel

    // Create google map object to be used for modification within fragment
    // Dont show map until location is set
    private var map : GoogleMap? = null
    private var isMapReady = false

    // Stored locations
    private var userLocation: Location? = null

    // Create location service and bool value for to know when to bind it and clean up
    private var locationService: LocationService? = null
    private var locationBound: Boolean = false

    // Coordinate service for fetching locations
    private var firebaseService: FirebaseService? = null
    private var firebaseBound: Boolean = false

    // Search radius for getting postings
    private var searchRadius : Double = 1.0

    private fun moveMapCamera(location: Location){
        placesFragment.clearList()
        map?.let { googleMap ->
            val latLng = LatLng(location.latitude, location.longitude)
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        }
    }
    private fun showPostingsInRadius(locationLatLng: LatLng, radiusInKm: Double){
        val postings = firebaseService?.fetchTripsByLocation(locationLatLng.latitude, locationLatLng.longitude, radiusInKm, true)
        postings?.addOnSuccessListener {
            for (document in it) {
                Log.e("MapsTeswt", "showpostings broken")
                val dataModel = document.toObject(Trips::class.java)
                if (dataModel != null) {
                    Log.e("MapsTeswt", dataModel.driverId)
                }
            }
        }?.addOnFailureListener {
            Toast.makeText(requireContext(), "Error Finding Posts", Toast.LENGTH_SHORT).show()
        }

        /*val postings = firebaseService?.fetchCoordinatesByLocation(locationLatLng.latitude, locationLatLng.longitude, radiusInKm)
        postings?.addOnSuccessListener { documentSnapshot ->
            for (document in documentSnapshot) {
                val dataModel = document.toObject(Coordinate::class.java)
                if (dataModel != null) {
                    val postLatLng = LatLng(dataModel.latitude, dataModel.longitude)
                    map?.addMarker(MarkerOptions().position(postLatLng).title(dataModel.id))
                }
            }
        }?.addOnFailureListener {
            Toast.makeText(requireContext(), "Error Finding Posts", Toast.LENGTH_SHORT).show()
        }*/


    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        if (savedInstanceState == null) {
            placesFragment = PlacesFragment()
            childFragmentManager.commit {
                add(R.id.places_fragment_container, placesFragment)
            }
        } else {
            placesFragment = childFragmentManager.findFragmentById(R.id.places_fragment_container) as PlacesFragment
        }

        placesViewModel = ViewModelProvider(requireActivity()).get(PlacesViewModel::class.java)

        // Recenter button bindings and listener
        val recenterButton: MaterialButton = binding.btnRecenter
        recenterButton.setOnClickListener {
            placesFragment.clearList()
            drawRadius()
            userLocation?.let {
                moveMapCamera(it)
            }

        }

        val searchButton: MaterialButton = binding.btnSearch
        searchButton.setOnClickListener {
            placesFragment.clearList()
            drawRadius()
            val cameraPosition = map?.cameraPosition?.target
            cameraPosition?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                showPostingsInRadius(latLng, searchRadius)
            }
        }

        val sliderLabel : TextView = binding.textRadius

        val radiusSlider: Slider = binding.sliderRadius
        searchRadius = radiusSlider.value.toDouble()
        radiusSlider.addOnChangeListener { _, value, _ ->
            placesFragment.clearList()
            searchRadius = value.toDouble()
            drawRadius()
            sliderLabel.text = buildString {
                append("$searchRadius")
                append(" km")
            }
        }


        // Top search bar binding and listener
        searchView = binding.mapSearchView

        // allow for clicking anywhere on search view to search
        searchView.isIconified = false

        // TODO: clear predictions after selected item
        // Set search view to selected prediction
        placesViewModel.getSelectedPrediction().observe(viewLifecycleOwner, Observer { prediction ->
            searchView.setQuery(prediction, true)
        })

        searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener{
            override fun onQueryTextSubmit(query: String?): Boolean {
                placesFragment.clearList()
                query?.let { locationSearch(it) }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Check if text is null otherwise get recommendations based on user search
                if(!newText.isNullOrEmpty()) {
                    placesViewModel.getAutocompletePredictions(newText).observe(viewLifecycleOwner, Observer { predictions ->
                        placesFragment.updateList(predictions)
                    })
                } else {
                    placesFragment.clearList()
                }
                return true
            }
        })

        initializeMap()
        return root
    }

    private fun drawRadius(){
        val cameraPosition = map?.cameraPosition?.target
        cameraPosition?.let {
            map?.clear()
            map?.addCircle(
                CircleOptions()
                    .center(it)
                    .radius(searchRadius * 1000)
                    .strokeColor(0xFF0000FF.toInt())
                    .fillColor(0x220000FF)
                    .strokeWidth(5f)
            )
            map?.addCircle(
                CircleOptions()
                    .center(it)
                    .radius(10.0)
                    .strokeColor(0xFF0000FF.toInt())
                    .fillColor(0x220000FF)
                    .strokeWidth(10f)
            )
        }


    }
    private fun initializeMap(){
        checkLocationPermissions()
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync { googleMap ->
            map = googleMap
            googleMap.isBuildingsEnabled = true
            isMapReady = true
            userLocation?.let { moveMapCamera(it) }
            googleMap.setOnCameraIdleListener {
                    drawRadius()
            }
        }
    }
    override fun onMapReady(p0: GoogleMap) {
        map = p0
        checkLocationPermissions()
    }
    override fun onStart() {
        super.onStart()
        if(arePermissionsGranted()){
            // Bind to LocationService, uses Bind_Auto_create to create service if it does not exist
            val serviceIntent = Intent(requireContext(), LocationService::class.java)
            requireContext().bindService(serviceIntent, locationConnection, Context.BIND_AUTO_CREATE)
            locationBound = true
        }
        val serviceIntent = Intent(requireContext(), FirebaseService::class.java)
        requireContext().bindService(serviceIntent, firebaseConnection, Context.BIND_AUTO_CREATE)
        firebaseBound = true
    }
    // Stop location service if still bound
    override fun onStop() {
        super.onStop()
        if (locationBound) {
            requireContext().unbindService(locationConnection)
            locationBound = false
        }
        if (firebaseBound){
            requireContext().unbindService(firebaseConnection)
            firebaseBound = false
        }
    }
    // Stop services if still bound
    override fun onDestroyView() {
        super.onDestroyView()
        if (locationBound) {
            requireContext().unbindService(locationConnection)
            locationBound = false
        }
        if(firebaseBound){
            requireContext().unbindService(firebaseConnection)
            firebaseBound = false
        }
    }

    private val firebaseConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FirebaseService.FirebaseBinder
            firebaseService = binder.getService()
            firebaseBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            firebaseBound = false
        }
    }


    // TODO: cleanup location connection to use location service data properly
    // Create service connection to get location data to maps
    private val locationConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationService.LocationBinder
            locationService = binder.getService()
            locationBound = true

            // Observe LiveData from the service
            locationService?.getLiveLocationData()?.observe(viewLifecycleOwner, Observer<Location>{
                userLocation = it
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationBound = false
        }
    }

    // Register for location permissions result
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            // Bind to LocationService, uses Bind_Auto_create to create service if it does not exist
            // TODO: Take binding out of specific functions and make it its own private function
            val serviceIntent = Intent(requireContext(), LocationService::class.java)
            requireContext().bindService(serviceIntent, locationConnection, Context.BIND_AUTO_CREATE)
        } else {
            Toast.makeText(requireContext(), "Location permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    // TODO: Possibly make permission manager so dont need to handle it all in one fragment
    // Check if location permissions are granted
    private fun arePermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    // Request location permissions
    private fun checkLocationPermissions() {
        if (!arePermissionsGranted()) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // Search for location using geocoder
    private fun locationSearch(location: String){
        // locale.getdefault gets users deafult language and other preferences
        // Use requireContext() to ensure context is not null
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        try {
            // Using deprecated function because newer version does not work with out min sdk setup
            // If min sdk updated replace with geocoder listener setup in android documentation
            val addressList = geocoder.getFromLocationName(location, 1)
            if (!addressList.isNullOrEmpty()) {
                val address = addressList[0]
                val latLng = LatLng(address.latitude, address.longitude)
                // Clear map so that old markers dont remain when moving across the map
                map?.clear()
                map?.addMarker(MarkerOptions().position(latLng).title(location))
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
            } else {
                Toast.makeText(requireContext(), "Location not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}