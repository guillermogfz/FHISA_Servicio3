package com.example.guill.fhisa_servicio3;

/**
 * Created by guill on 11/11/2017.
 */

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.example.guill.fhisa_servicio3.Objetos.BaseOperativa;
import com.example.guill.fhisa_servicio3.Objetos.Camion;
import com.example.guill.fhisa_servicio3.Objetos.Posicion;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * A bound and started service that is promoted to a foreground service when location updates have
 * been requested and all clients unbind.
 *
 * For apps running in the background on "O" devices, location is computed only once every 10
 * minutes and delivered batched every 30 minutes. This restriction applies even to apps
 * targeting "N" or lower which are run on "O" devices.
 *
 * This sample show how to use a long-running service for location updates. When an activity is
 * bound to this service, frequent location updates are permitted. When the activity is removed
 * from the foreground, the service promotes itself to a foreground service, and location updates
 * continue. When the activity comes back to the foreground, the foreground service stops, and the
 * notification assocaited with that service is removed.
 */
public class LocationUpdatesService extends Service {

    private static final String PACKAGE_NAME =
            "com.google.android.gms.location.sample.locationupdatesforegroundservice";

    private static final String TAG = LocationUpdatesService.class.getSimpleName();

    static final String ACTION_BROADCAST = PACKAGE_NAME + ".broadcast";

    static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";
    private static final String EXTRA_STARTED_FROM_NOTIFICATION = PACKAGE_NAME +
            ".started_from_notification";

    private final IBinder mBinder = new LocalBinder();

    /**
     * The desired interval for location updates. Inexact. Updates may be more or less frequent.
     */
    private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 60 * 1000;

    /**
     * The fastest rate for active location updates. Updates will never be more frequent
     * than this value.
     */
    private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    /**
     * The identifier for the notification displayed for the foreground service.
     */
    private static final int NOTIFICATION_ID = 12345678;

    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    private boolean mChangingConfiguration = false;

    private NotificationManager mNotificationManager;

    /**
     * Contains parameters used by {@link com.google.android.gms.location.FusedLocationProviderApi}.
     */
    private LocationRequest mLocationRequest;

    /**
     * Provides access to the Fused Location Provider API.
     */
    private FusedLocationProviderClient mFusedLocationClient;

    /**
     * Callback for changes in location.
     */
    private LocationCallback mLocationCallback;

    private Handler mServiceHandler;

    /**
     * The current location.
     */
    private Location mLocation;

    SharedPreferences preferences;

    ArrayList<BaseOperativa> listaBasesOperativas;

    final FirebaseDatabase database = FirebaseDatabase.getInstance();

    final DatabaseReference areasRef = database.getReference("areas");

    final DatabaseReference camionesRef = database.getReference(Utils.FIREBASE_CAMIONES_REFERENCE);

    final DatabaseReference frecuenciasRef = database.getReference("frecuencias");

    final DatabaseReference bateriaRef = database.getReference("bateria");

    long FRECUENCIA_POSICIONES;

    public LocationUpdatesService() {
    }

    @Override
    public void onCreate() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onNewLocation(locationResult.getLastLocation());
            }
        };

        createLocationRequest();
        getLastLocation();

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mServiceHandler = new Handler(handlerThread.getLooper());
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");

    //    preferences = PreferenceManager.getDefaultSharedPreferences(this);
        saveAreas();
    /*    String json = preferences.getString("jsonListaAreas", "");
        Log.i("JSON", "JSON:" + json);
        Gson gson = new Gson();
        String jsonListaAreas = preferences.getString("jsonListaAreas", "");
        Type type = new TypeToken<List<BaseOperativa>>(){}.getType();
        listaBasesOperativas = gson.fromJson(jsonListaAreas, type); */

        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION,
                false);

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            removeLocationUpdates();
            stopSelf();
        }
        // Tells the system to try to recreate the service after it has been killed.
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mChangingConfiguration = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Log.i(TAG, "in onBind()");
        stopForeground(true);
        mChangingConfiguration = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.i(TAG, "in onRebind()");
        stopForeground(true);
        mChangingConfiguration = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Last client unbound from service");

        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.
        if (!mChangingConfiguration && Utils.requestingLocationUpdates(this)) {
            Log.i(TAG, "Starting foreground service");
            /*
            // TODO(developer). If targeting O, use the following code.
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                mNotificationManager.startServiceInForeground(new Intent(this,
                        LocationUpdatesService.class), NOTIFICATION_ID, getNotification());
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
             */
            saveAreas();
            startForeground(NOTIFICATION_ID, getNotification());
        }
        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        mServiceHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Makes a request for location updates. Note that in this sample we merely log the
     * {@link SecurityException}.
     */
    public void requestLocationUpdates() {
        Log.i(TAG, "Requesting location updates");
        Utils.setRequestingLocationUpdates(this, true);
        startService(new Intent(getApplicationContext(), LocationUpdatesService.class));

        startForeground(NOTIFICATION_ID, getNotification()); //Añadido por mi para que se muestre la barra de notificaciones al reboot

        try {
            mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                    mLocationCallback, Looper.myLooper());
        } catch (SecurityException unlikely) {
            Utils.setRequestingLocationUpdates(this, false);
            Log.e(TAG, "Lost location permission. Could not request updates. " + unlikely);
        }
    }

    /**
     * Removes location updates. Note that in this sample we merely log the
     * {@link SecurityException}.
     */
    public void removeLocationUpdates() {
        Log.i(TAG, "Removing location updates");
        try {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
            Utils.setRequestingLocationUpdates(this, false);
            stopSelf();
        } catch (SecurityException unlikely) {
            Utils.setRequestingLocationUpdates(this, true);
            Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
        }
    }

    /**
     * Returns the {@link NotificationCompat} used as part of the foreground service.
     */
    private Notification getNotification() {
        Intent intent = new Intent(this, LocationUpdatesService.class);

        CharSequence text = Utils.getLocationText(mLocation);

        // Extra to help us figure out if we arrived in onStartCommand via the notification or not.
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

        // The PendingIntent that leads to a call to onStartCommand() in this service.
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // The PendingIntent to launch activity.
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_fhisa_verde);

        return new NotificationCompat.Builder(this)
           /*     .addAction(R.drawable.ic_launch, getString(R.string.launch_activity),
                        activityPendingIntent)
                .addAction(R.drawable.ic_cancel, getString(R.string.remove_location_updates),
                        servicePendingIntent) */
                .setContentText(text)
                .setContentTitle(Utils.getLocationTitle(this))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.ic_fhisa)
                .setLargeIcon(largeIcon)
                .setTicker(text)
                .setWhen(System.currentTimeMillis()).build();
    }

    private void getLastLocation() {
        try {
            mFusedLocationClient.getLastLocation()
                    .addOnCompleteListener(new OnCompleteListener<Location>() {
                        @Override
                        public void onComplete(@NonNull Task<Location> task) {
                            if (task.isSuccessful() && task.getResult() != null) {
                                mLocation = task.getResult();

                                getUpdateInterval();
                                sendDataFirebase();


                            } else {
                                Log.w(TAG, "Failed to get location.");
                            }
                        }
                    });
        } catch (SecurityException unlikely) {
            Log.e(TAG, "Lost location permission." + unlikely);
        }
    }

    private void onNewLocation(Location location) {
        Log.i(TAG, "New location: " + location);

        mLocation = location;

        sendDataFirebase();

        // Notify anyone listening for broadcasts about the new location.
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtra(EXTRA_LOCATION, location);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

        // Update notification content if running as a foreground service.
        if (serviceIsRunningInForeground(this)) {
            mNotificationManager.notify(NOTIFICATION_ID, getNotification());
        }
    }

    /**
     * Sets the location request parameters.
     */
    private void createLocationRequest() {
        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        FRECUENCIA_POSICIONES = preferences.getLong("frecuencia", 60*1000);

        mLocationRequest = new LocationRequest();
        //mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
       // mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setInterval(FRECUENCIA_POSICIONES);
        if (FRECUENCIA_POSICIONES==60000) {
            mLocationRequest.setFastestInterval(FRECUENCIA_POSICIONES/2);
        } else {
            mLocationRequest.setFastestInterval(FRECUENCIA_POSICIONES-20000);
        }
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        LocationUpdatesService getService() {
            return LocationUpdatesService.this;
        }
    }

    /**
     * Returns true if this is a foreground service.
     *
     * @param context The {@link Context}.
     */
    public boolean serviceIsRunningInForeground(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
                Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Método para obtener el IMEI del dispositivo en versiones recientes
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public String getImeiOreo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(this.TELEPHONY_SERVICE);
        String imei = tm.getImei();
        return imei;
    }

    /**
     * Método para obtener el IMEI del dispositivo en versiones antiguas
     *
     */
    public String getIMEILow(){
        TelephonyManager telephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        String imei = telephonyManager.getDeviceId();
        return imei;
    }

    /**
     * Metodo para enviar los datos a Firebase
     */
    public void sendDataFirebase(){
        String id;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            id = getImeiOreo();
        } else {
            id = getIMEILow();
        }

        Posicion posicion = new Posicion(mLocation.getAltitude(), mLocation.getLatitude(), mLocation.getLongitude(),
                mLocation.getSpeed(), mLocation.getTime());

        Camion camion = new Camion(id, posicion);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();

        Gson gson = new Gson();
        String jsonListaAreas = preferences.getString("jsonListaAreas", "");
        Type type = new TypeToken<List<BaseOperativa>>(){}.getType();
        listaBasesOperativas = gson.fromJson(jsonListaAreas, type);
        int posRutaActual = preferences.getInt("posRutaActual", 0);
        Log.i("posRutaActual", String.valueOf(posRutaActual));

        if (listaBasesOperativas == null) {
            saveAreas();
        } else {
            Log.i("CamionDentro", String.valueOf(camionDentro(posicion, listaBasesOperativas)));

            if (!camionDentro(posicion, listaBasesOperativas) && posRutaActual == 0) { //Si sale por primera vez del area
                //Falseo la primera posicion con el area de salida
                Posicion posicionAreaSalida = areaProxima(camion.getPosicion(), listaBasesOperativas);
                camionesRef.child(camion.getId()).child("rutas").child("ruta_actual").push().setValue(posicionAreaSalida);
                camionesRef.child(camion.getId()).child("rutas").child("ruta_actual").push().setValue(camion.getPosicion());
                editor.putInt("posRutaActual", posRutaActual + 1);
                editor.apply();
                camionesRef.child(camion.getId()).child("bateria").setValue(getBatteryPercentage(this));

            } else if (!camionDentro(posicion, listaBasesOperativas) && posRutaActual > 0) { //Si está fuera y ya lleva un tiempo
                camionesRef.child(camion.getId()).child("rutas").child("ruta_actual").push().setValue(camion.getPosicion());
                editor.putInt("posRutaActual", posRutaActual + 1);
                editor.apply();
                camionesRef.child(camion.getId()).child("bateria").setValue(getBatteryPercentage(this));

            } else if (camionDentro(posicion, listaBasesOperativas) && posRutaActual > 0) { //Si hay mas de una pos. es pq el camión estaba en ruta y acaba de llegar
                //String nombreRuta = preferences.getString("nombreRuta", "");
                String nombreRuta = "ruta_" + Utils.getFechaHoraActual();
                posRutaActual = 0;
                editor.putInt("posRutaActual", posRutaActual);
                camionesRef.child(camion.getId()).child("rutas").child("ruta_actual").push().setValue(camion.getPosicion()); //Ponemos la ultima posicion dentro del area
                moverRuta(camionesRef.child(camion.getId()).child("rutas").child("ruta_actual"),
                        camionesRef.child(camion.getId()).child("rutas").child("rutas_completadas").child(nombreRuta));
                editor.apply();
                camionesRef.child(camion.getId()).child("bateria").setValue(getBatteryPercentage(this));

            }
        }
    }

    public void saveAreas() {

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("jsonListaAreas");

        areasRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                SharedPreferences.Editor editor = preferences.edit();
                ArrayList<BaseOperativa> listaBasesOperativas = new ArrayList<>();
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    snapshot.getValue().getClass();
                    BaseOperativa baseOperativaFirebase = snapshot.getValue(BaseOperativa.class);
                    listaBasesOperativas.add(baseOperativaFirebase);
                }
                Gson gson = new Gson();
                String jsonListaAreas = gson.toJson(listaBasesOperativas);
                editor.putString("jsonListaAreas", jsonListaAreas);
                editor.apply();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }


    public Posicion areaProxima(Posicion posicion, ArrayList<BaseOperativa> listaBasesOperativas) {
        float distanciaMax = 1000;
        BaseOperativa baseOperativaProxima = null;

        float distanciaAnterior = 100000000;

        for (int i = 0; i < listaBasesOperativas.size(); i++) {
            float[] distance = new float[2];
            BaseOperativa baseOperativaActual = listaBasesOperativas.get(i);
            Location.distanceBetween(posicion.getLatitude(), posicion.getLongitude(),
                    baseOperativaActual.getLatitud(), baseOperativaActual.getLongitud(), distance);

            Log.i("AreaProxima", "Distancia de " + baseOperativaActual.getIdentificador() + ": " +distance[0]);

            if (distance[0] > baseOperativaActual.getDistancia() && distance[0] < distanciaAnterior) {
                Log.i("AreaProxima", "Seteo AreaProxima");
                baseOperativaProxima = baseOperativaActual;
                distanciaAnterior = distance[0];
            }

            //distanciaAnterior = distance[0];
        }

        Log.i("AreaProxima", "La mas proxima es: " + baseOperativaProxima.getIdentificador());
        Posicion posicionArea = new Posicion(0, baseOperativaProxima.getLatitud(), baseOperativaProxima.getLongitud(), 0, posicion.getTime());
        return posicionArea;
    }

    /**
     * Método para comprobar si un camión se encuentra dentro alguna de las areas
     **/
    public boolean camionDentro(Posicion posicion, ArrayList<BaseOperativa> listaBasesOperativas) {
        ArrayList<Integer> d = new ArrayList<>();
        boolean dentro = false;

        for (int i = 0; i < listaBasesOperativas.size(); i++) {
            float[] distance = new float[2];
            Location.distanceBetween(posicion.getLatitude(), posicion.getLongitude(),
                    listaBasesOperativas.get(i).getLatitud(), listaBasesOperativas.get(i).getLongitud(), distance);

            if (distance[0] <= listaBasesOperativas.get(i).getDistancia()) { //Camion dentro del circulo
                // Inside The Circle
                dentro = true;
                d.add(1);
            } else {
                dentro = false;
                d.add(0);
            }
        }

        for (int i=0; i<d.size(); i++) {
            if (d.get(i) == 1) dentro = true;
        }
        return dentro;
    }

    public void moverRuta(DatabaseReference fromPath, final DatabaseReference toPath) {
        fromPath.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(final DataSnapshot dataSnapshot) {
                toPath.setValue(dataSnapshot.getValue(), new DatabaseReference.CompletionListener() {
                    @Override
                    public void onComplete(DatabaseError firebaseError, DatabaseReference firebase) {
                        if (firebaseError != null) {
                            System.out.println("Copy failed");
                        } else {
                            dataSnapshot.getRef().removeValue();
                            System.out.println("Success");
                        }
                    }
                });
            }

            @Override
            public void onCancelled(DatabaseError firebaseError) {}
        });
    }

    /**
     * Devuelve el intervalo de actualización de posición definido por el usuario gestor
     */
    public void getUpdateInterval() {
        //DEFAULT : UPDATE_INTERVAL_IN_MILLISECONDS

        String imei;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            imei = getImeiOreo();
        } else {
            imei = getIMEILow();
        }

        camionesRef.child(imei).child("frecuencia_posiciones").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.i("Frecuencia", "Estoy dentro del DataSnapshot");

                long frecuenciaPosiciones;

                if (dataSnapshot.exists()) {
                    frecuenciaPosiciones = (long) dataSnapshot.getValue()*60*1000;
                    Log.i("frecuenciaposiciones", "Val:" + frecuenciaPosiciones);
                } else {
                    frecuenciaPosiciones = 60*1000;
                }

                preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                SharedPreferences.Editor editor = preferences.edit();
                editor.putLong("frecuencia", frecuenciaPosiciones);
                editor.apply();

                Log.i("Frecuencia", String.valueOf(frecuenciaPosiciones));

                if (frecuenciaPosiciones!=60000) {
                    mLocationRequest.setInterval(frecuenciaPosiciones);
                    mLocationRequest.setFastestInterval(frecuenciaPosiciones-20000);
                } else { //Si es un minuto ponemos que se puedan enviar cada 30 segundos, si es posible.
                    mLocationRequest.setInterval(frecuenciaPosiciones);
                    mLocationRequest.setFastestInterval(frecuenciaPosiciones/2);
                }
                //removeLocationUpdates();
                requestLocationUpdates();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    public static int getBatteryPercentage(Context context) {

        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, iFilter);

        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;

        float batteryPct = level / (float) scale;

        return (int) (batteryPct * 100);
    }
}