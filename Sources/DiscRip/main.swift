import CDiscRip
import Foundation
var err: UnsafeMutablePointer<CChar>? = nil
guard let d = dr_disc_open(CommandLine.arguments[1], nil, &err) else { exit(1) }
print(String(cString: dr_disc_info_json(d)!))
