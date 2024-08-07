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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.watpool.R
import com.example.watpool.databinding.FragmentMapsBinding
import com.example.watpool.services.FirebaseService
import com.example.watpool.services.models.Coordinate
import com.example.watpool.services.LocationService
import com.example.watpool.services.models.Trips
import com.example.watpool.services.models.Postings
import com.example.watpool.ui.postingList.PostingDetailFragment
import com.example.watpool.ui.postingList.PostingListFragment
import com.example.watpool.ui.tripList.TripListFragmentDirections
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.firebase.firestore.toObject
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
    private lateinit var startSearchView : SearchView
    private lateinit var destinationSearchView: SearchView

    private lateinit var startPlacesFragment: PlacesFragment
    private lateinit var destinationPlacesFragment: PlacesFragment

    private lateinit var postingBottomSheet: PostingListFragment

    // View Models
    private val mapsViewModel: MapsViewModel by activityViewModels()
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
    private var startSearchRadius : Double = 1.0
    private var destinationSearchRadius : Double = 1.0

    // Current destination of search views
    private var startDestinationLocation : String = ""
    private var endDestinationLocation : String = ""

    // Bool to determine which search has focus
    private var isEndDestinationFocused: Boolean = false
    private var isStartDestinationFocused: Boolean = false

    // Determine if prediction in progress so list can go away
    private var isSelection: Boolean = false

    private fun moveMapCamera(location: Location){
        startPlacesFragment.clearList()
        destinationPlacesFragment.clearList()
        map?.let { googleMap ->
            val latLng = LatLng(location.latitude, location.longitude)
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))

        }

    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchPostingsInRadius(locationLatLng: LatLng, radiusInKm: Double){

        // Nothing searched so just find trips in radius
        if(startDestinationLocation.isEmpty() && endDestinationLocation.isEmpty()){
            // Get all trips with destination in radius of camera
            map?.let {
                val cameraPosition = it.cameraPosition.target
                firebaseService?.let {  firebase ->
                    mapsViewModel.fetchPostingsByEnd(firebase, cameraPosition.latitude, cameraPosition.longitude, radiusInKm, false)
                }
            }
        } else if(startDestinationLocation.isEmpty() && endDestinationLocation.isNotEmpty()) {
            map?.let {
                val endLocation = getLatLngByLocation(endDestinationLocation)
                it.animateCamera(CameraUpdateFactory.newLatLngZoom(endLocation, 15f))
                firebaseService?.let {  firebase ->
                    mapsViewModel.fetchPostingsByEnd(firebase, endLocation.latitude, endLocation.longitude, radiusInKm, false)
                }
            }
        }else if(startDestinationLocation.isNotEmpty() && endDestinationLocation.isEmpty()) {
                map?.let {
                    val startLocation = getLatLngByLocation(startDestinationLocation)
                    it.animateCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 15f))
                    firebaseService?.let {  firebase ->
                        mapsViewModel.fetchPostingsByEnd(firebase, startLocation.latitude, startLocation.longitude, radiusInKm, true)
                    }
                }
        } else {
            map?.let {
                val endLocation = getLatLngByLocation(endDestinationLocation)
                val startLocation = getLatLngByLocation(startDestinationLocation)
                it.animateCamera(CameraUpdateFactory.newLatLngZoom(endLocation, 15f))
                firebaseService?.let {  firebase ->
                    mapsViewModel.fetchPostingsByStartAndEnd(firebase, startLocation.latitude, startLocation.longitude, radiusInKm, endLocation.latitude, endLocation.longitude, radiusInKm)
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        startPlacesFragment.clearList()
        destinationPlacesFragment.clearList()
        startSearchView.setQuery("", true)
        destinationSearchView.setQuery("", true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        if (savedInstanceState == null) {
            startPlacesFragment = PlacesFragment()
            destinationPlacesFragment = PlacesFragment()
            childFragmentManager.commit {
                add(R.id.places_fragment_start_container, startPlacesFragment)
                add(R.id.places_fragment_destination_container, destinationPlacesFragment)
            }
        } else {
            startPlacesFragment = childFragmentManager.findFragmentById(R.id.places_fragment_start_container) as PlacesFragment
            destinationPlacesFragment = childFragmentManager.findFragmentById(R.id.places_fragment_destination_container) as PlacesFragment
        }

        placesViewModel = ViewModelProvider(requireActivity()).get(PlacesViewModel::class.java)

        // Recenter button bindings and listener
        val recenterButton: MaterialButton = binding.btnRecenter
        recenterButton.setOnClickListener {
            startPlacesFragment.clearList()
            destinationPlacesFragment.clearList()
            drawRadius()
            userLocation?.let {
                moveMapCamera(it)
            }

        }

        postingBottomSheet = PostingListFragment()

        val searchButton: MaterialButton = binding.btnSearch
        searchButton.setOnClickListener {
            startPlacesFragment.clearList()
            destinationPlacesFragment.clearList()
            //drawRadius()
            val cameraPosition = map?.cameraPosition?.target
            cameraPosition?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                fetchPostingsInRadius(latLng, startSearchRadius)
            }

        }

        val sliderLabel : TextView = binding.textRadius

        val radiusSlider: Slider = binding.sliderRadius
        startSearchRadius = radiusSlider.value.toDouble()
        radiusSlider.addOnChangeListener { _, value, _ ->
            startPlacesFragment.clearList()
            destinationPlacesFragment.clearList()
            startSearchRadius = value.toDouble()
            drawRadius()
            sliderLabel.text = buildString {
                append("$startSearchRadius")
                append(" km")
            }
        }

        mapsViewModel.postingsInRadius.observe(viewLifecycleOwner, Observer { postings ->
            if(postings.isNotEmpty()){
                map?.let { googleMap ->
                    for(post in postings){
                        val startLatLng = LatLng(post.startCoords.latitude, post.startCoords.longitude)
                        googleMap.addMarker(MarkerOptions().position(startLatLng).title(post.startCoords.location).icon(
                            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))

                        val endLatLng = LatLng(post.endCoords.latitude, post.endCoords.longitude)
                        googleMap.addMarker(MarkerOptions().position(endLatLng).title(post.endCoords.location).icon(
                            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
                    }
                }
                postingBottomSheet.show(requireActivity().supportFragmentManager, "SupportBottomSheet")
            }
        })

        // Top search bar binding and listener
        startSearchView = binding.mapSearchViewStart
        destinationSearchView = binding.mapSearchViewDestination

        // allow for clicking anywhere on search view to search
        startSearchView.isIconified = false
        destinationSearchView.isIconified = false

        startSearchView.clearFocus()
        destinationSearchView.clearFocus()

        // Set search view to selected prediction
        placesViewModel.getSelectedPrediction().observe(viewLifecycleOwner, Observer { prediction ->
            if(startSearchView.hasFocus()){
                isSelection = true
                startSearchView.setQuery(prediction, true)
                startDestinationLocation = prediction
                startPlacesFragment.clearList()
                destinationPlacesFragment.clearList()
                isSelection = false
            }
            if(destinationSearchView.hasFocus()){
                isSelection = true
                destinationSearchView.setQuery(prediction, true)
                endDestinationLocation = prediction
                startPlacesFragment.clearList()
                destinationPlacesFragment.clearList()
                isSelection = false
            }
        })

        startSearchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener{
            override fun onQueryTextSubmit(query: String?): Boolean {
                startPlacesFragment.clearList()
                destinationPlacesFragment.clearList()
                startSearchView.clearFocus()
                query?.let {
                   startDestinationLocation =  locationSearch(it)
                }

                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Check if text is null otherwise get recommendations based on user search
                if(!isSelection){
                    if(!newText.isNullOrEmpty()) {
                        placesViewModel.getAutocompletePredictions(newText).observe(viewLifecycleOwner, Observer { predictions ->
                            startPlacesFragment.updateList(predictions)
                        })
                    } else {
                        startDestinationLocation =""
                        startPlacesFragment.clearList()
                    }
                }
                return true
            }
        })

        destinationSearchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener{
            override fun onQueryTextSubmit(query: String?): Boolean {
                startPlacesFragment.clearList()
                destinationPlacesFragment.clearList()
                destinationSearchView.clearFocus()
                query?.let {
                    endDestinationLocation = locationSearch(it)
                }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Check if text is null otherwise get recommendations based on user search
                if(!isSelection){
                    if(!newText.isNullOrEmpty()) {
                        placesViewModel.getAutocompletePredictions(newText).observe(viewLifecycleOwner, Observer { predictions ->
                            destinationPlacesFragment.updateList(predictions)
                        })
                    } else {
                        endDestinationLocation = ""
                        destinationPlacesFragment.clearList()
                    }
                }
                return true
            }
        })


        startSearchView.setOnQueryTextFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onFocusChanged(startSearchView, true)
            } else {
                onFocusChanged(startSearchView, false)
            }
        }

        destinationSearchView.setOnQueryTextFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onFocusChanged(destinationSearchView, true)
            } else {
                onFocusChanged(destinationSearchView, false)
            }
        }

        val createButton: MaterialButton = binding.btnCreate
        createButton.setOnClickListener {
            createTrip()
        }

        initializeMap()
        return root
    }

    private fun createTrip(){
        val action = MapsFragmentDirections.actionMapFragmentToCreateTripFragment(startDestinationLocation, endDestinationLocation)
        startPlacesFragment.clearList()
        destinationPlacesFragment.clearList()
        startPlacesFragment.onDestroy()
        destinationPlacesFragment.onDestroy()
        findNavController().navigate(action)
    }

    private fun drawRadius(){
        val cameraPosition = map?.cameraPosition?.target
        cameraPosition?.let {
            map?.clear()
            if(isStartDestinationFocused){
                map?.addCircle(
                    CircleOptions()
                        .center(it)
                        .radius(startSearchRadius * 1000)
                        .strokeColor(0xFF00FF00.toInt())
                        .fillColor(0x2200FF00)
                        .strokeWidth(5f)
                )
                map?.addCircle(
                    CircleOptions()
                        .center(it)
                        .radius(10.0)
                        .strokeColor(0xFF00FF00.toInt())
                        .fillColor(0x2200FF00)
                        .strokeWidth(10f)
                )
            } else {
                map?.addCircle(
                    CircleOptions()
                        .center(it)
                        .radius(startSearchRadius * 1000)
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
            val serviceIntent = Intent(requireContext(), LocationService::class.java)
            requireContext().bindService(serviceIntent, locationConnection, Context.BIND_AUTO_CREATE)
        } else {
            Toast.makeText(requireContext(), "Location permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

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
    private fun locationSearch(location: String) : String{
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
                return address.getAddressLine(0)
            } else {
                Toast.makeText(requireContext(), "Location not found", Toast.LENGTH_SHORT).show()
                return ""
            }
        } catch (e: IOException) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            return ""
        }
    }

    private fun getLatLngByLocation(location: String) : LatLng{
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

                return latLng
            } else {
                Toast.makeText(requireContext(), "Location not found", Toast.LENGTH_SHORT).show()
                return LatLng(0.0, 0.0)
            }
        } catch (e: IOException) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            return LatLng(0.0, 0.0)
        }
    }

    private fun locationSearchCoordinate(lat: Double, lng: Double): String{
        // locale.getdefault gets users deafult language and other preferences
        // Use requireContext() to ensure context is not null
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        try {
            // Using deprecated function because newer version does not work with out min sdk setup
            // If min sdk updated replace with geocoder listener setup in android documentation
            val addressList = geocoder.getFromLocation(lat, lng, 5)
            if (!addressList.isNullOrEmpty()) {
                val address = addressList[0]
                // Clear map so that old markers dont remain when moving across the map
                return address.getAddressLine(0)
            } else {
                Toast.makeText(requireContext(), "Location not found", Toast.LENGTH_SHORT).show()
                return ""
            }
        } catch (e: IOException) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            return ""
        }
    }

    private fun onFocusChanged(view: SearchView, hasFocus: Boolean) {
        when (view.id) {
            startSearchView.id -> {
                if (hasFocus) {
                    isStartDestinationFocused = true
                    drawRadius()

                } else {
                    isStartDestinationFocused = false
                    drawRadius()
                }
            }
            destinationSearchView.id -> {
                if (hasFocus) {
                    isEndDestinationFocused = true
                    drawRadius()
                } else {
                    isEndDestinationFocused = false
                    drawRadius()
                }
            }
        }
        // Check if neither has focus
        /*if (!startSearchView.hasFocus() && !destinationSearchView.hasFocus()) {
            // Handle the case where neither SearchView has focus
        }*/
    }
}