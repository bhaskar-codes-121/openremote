import UIKit
import CoreLocation

public class GeofenceProvider: NSObject, URLSessionDelegate {

    static let baseUrlKey = "baseUrl"
    static let consoleIdKey = "consoleId"
    static let geoPostUrlsKey = "geoPostUrls"

    let version = "ORConsole"
    let geofenceFetchEndpoint = "rules/geofences/"
    let locationManager = CLLocationManager()
    let userdefaults = UserDefaults.standard
    var geoPostUrls = [String:String]()
    var initializeCallback : (([String: Any]) -> (Void))?

    public var baseURL: String = ""
    public var consoleId: String = ""

    public override init() {
        super.init()
        locationManager.delegate = self
        self.baseURL = userdefaults.string(forKey: GeofenceProvider.baseUrlKey) ?? ""
        self.consoleId = userdefaults.string(forKey: GeofenceProvider.consoleIdKey) ?? ""
        self.geoPostUrls = userdefaults.value(forKey: GeofenceProvider.geoPostUrlsKey) as? [String:String] ?? [String:String]()
    }

    public func initialize(callback:@escaping ([String: Any]) ->(Void)) {
        initializeCallback = callback
        if checkPermission() {
            initializeCallback?([
                DefaultsKey.actionKey: Actions.providerInit,
                DefaultsKey.providerKey: Providers.geofence,
                DefaultsKey.versionKey: version,
                DefaultsKey.requiresPermissionKey: true,
                DefaultsKey.hasPermissionKey: checkPermission(),
                DefaultsKey.successKey: true
                ])
        } else {
            registerPermissions()
        }
    }

    private func checkPermission() -> Bool {
        if CLLocationManager.locationServicesEnabled() {
            if CLLocationManager.authorizationStatus() == .authorizedAlways{
                return true
            }
        }
        return false
    }

    private func registerPermissions() {
        locationManager.requestAlwaysAuthorization()
    }

    public func addGeofence(geofence: GeofenceDefinition) {
        let region = CLCircularRegion(center: CLLocationCoordinate2DMake(geofence.lat, geofence.lng), radius: geofence.radius, identifier:geofence.id)
        locationManager.startMonitoring(for: region)
        geoPostUrls[geofence.id] = geofence.postUrl
        userdefaults.set(geoPostUrls, forKey: GeofenceProvider.geoPostUrlsKey)
        userdefaults.synchronize()
    }

    public func removeGeofence(id: String) {
        for region in locationManager.monitoredRegions {
            guard let circularRegion = region as? CLCircularRegion, circularRegion.identifier == id else {continue}
            locationManager.stopMonitoring(for: circularRegion)
            geoPostUrls.removeValue(forKey: circularRegion.identifier)
            userdefaults.set(geoPostUrls, forKey: GeofenceProvider.geoPostUrlsKey)
            userdefaults.synchronize()
            break
        }
    }

    public func clearAllRegions() {
        for region in locationManager.monitoredRegions {
            locationManager.stopMonitoring(for: region)
        }
        geoPostUrls = [String:String]()
        userdefaults.set(geoPostUrls, forKey: GeofenceProvider.geoPostUrlsKey)
        userdefaults.synchronize()
    }

    public func enable(baseUrl: String, consoleId: String)-> [String: Any] {
        self.baseURL = baseUrl
        self.consoleId = consoleId
        userdefaults.set(self.baseURL, forKey: GeofenceProvider.baseUrlKey)
        userdefaults.set(self.consoleId, forKey: GeofenceProvider.consoleIdKey)
        userdefaults.synchronize()

        return [
            DefaultsKey.actionKey: Actions.providerEnable,
            DefaultsKey.providerKey: Providers.geofence,
            DefaultsKey.hasPermissionKey: checkPermission(),
            DefaultsKey.successKey: true
        ]
    }

    public func disbale()-> [String: Any] {
        return [
            DefaultsKey.actionKey: "PROVIDER_DISABLE",
            DefaultsKey.providerKey: Providers.geofence
        ]
    }

    public func refreshGeofences() {
        fetchGeofences()
    }

    public func fetchGeofences(callback: (([GeofenceDefinition]) -> ())? = nil)  {
        if let tkurlRequest = URL(string: "\(baseURL)/\(geofenceFetchEndpoint)\(consoleId)") {
            let tkRequest = NSMutableURLRequest(url: tkurlRequest)
            tkRequest.httpMethod = "GET"
            let sessionConfiguration = URLSessionConfiguration.default
            let session = URLSession(configuration: sessionConfiguration, delegate: self, delegateQueue: nil)
            let req = session.dataTask(with: tkRequest as URLRequest, completionHandler: { (data, response, error) in
                if (data != nil){
                    guard let geofences = try? JSONDecoder().decode([GeofenceDefinition].self, from: data!) else {
                        callback?([])
                        return
                    }
                    self.clearAllRegions()
                    print("Geofences count: \(geofences.count)")
                    for geofence in geofences {
                        self.addGeofence(geofence: geofence)
                    }
                    callback?(geofences)
                } else {
                    NSLog("error %@", (error! as NSError).localizedDescription)
                    let error = NSError(domain: "", code: 0, userInfo:  [
                        NSLocalizedDescriptionKey :  NSLocalizedString("NoDataReceived", value: "Did not receive any data", comment: "")
                        ])
                    print(error)
                    callback?([])
                }
            })
            req.resume()
        } else {
            callback?([])
        }
    }

    private func sendGeofenceRequest(url: URL, data:[String: Any]?) {
        let request = NSMutableURLRequest(url: url)
        request.addValue("application/json", forHTTPHeaderField:"Content-Type");
        request.httpMethod = "POST"

        if (data != nil) {
            if let postBody = try? JSONSerialization.data(withJSONObject: data!, options: []) {
                request.httpBody = postBody
            }
        }

        let sessionConfiguration = URLSessionConfiguration.default
        let session = URLSession(configuration: sessionConfiguration, delegate: self, delegateQueue: nil)
        let req = session.dataTask(with: request as URLRequest, completionHandler: { (data, response, error) in
            if (data != nil){

            } else {
                NSLog("error %@", (error! as NSError).localizedDescription)
                let error = NSError(domain: "", code: 0, userInfo:  [
                    NSLocalizedDescriptionKey :  NSLocalizedString("NoDataReceived", value: "Did not receive any data", comment: "")
                    ])
                print(error)
            }
        })
        req.resume()
    }

    public class GeofenceDefinition: NSObject, Decodable {
        public var id: String = ""
        public var lat: Double = 0.0
        public var lng: Double = 0.0
        public var radius: Double = 0.0
        public var postUrl: String = ""
    }
}

extension GeofenceProvider: CLLocationManagerDelegate {
    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard let circularRegion = region as? CLCircularRegion else {return}

        guard let postString = geoPostUrls[circularRegion.identifier] else {
            return
        }
        guard let tkurlRequest = URL(string: "\(baseURL)\(postString)") else { return }

        let postData = [
            "objectValue" : [
                "type": "Point",
                "coordinates": [manager.location?.coordinate.longitude, manager.location?.coordinate.latitude]
            ]
        ]

        sendGeofenceRequest(url: tkurlRequest, data: postData)
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard let circularRegion = region as? CLCircularRegion else {return}

        guard let postString = geoPostUrls[circularRegion.identifier] else {
            return
        }
        guard let tkurlRequest = URL(string: "\(baseURL)\(postString)") else { return }

        sendGeofenceRequest(url: tkurlRequest, data: nil)
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if status != .notDetermined {
            initializeCallback?([
                DefaultsKey.actionKey: Actions.providerInit,
                DefaultsKey.providerKey: Providers.geofence,
                DefaultsKey.versionKey: version,
                DefaultsKey.requiresPermissionKey: true,
                DefaultsKey.hasPermissionKey: checkPermission(),
                DefaultsKey.successKey: true
                ])
        }
    }
}
