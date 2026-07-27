import SomedayIos
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    private var mainWindow: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = MainViewControllerKt.MainViewController()
        window.makeKeyAndVisible()
        mainWindow = window
        return true
    }
}
