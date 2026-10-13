require "json"

package = JSON.parse(File.read(File.join(__dir__, "..", "..", "package.json")))

Pod::Spec.new do |s|
  s.name         = "AegisScanRNBridge"
  s.version      = package["version"]
  s.summary      = "React Native bridge over the AegisScan SwiftPM package."
  s.homepage     = "https://github.com/deadislove/aegis_scan"
  s.license      = "Apache-2.0"
  s.author       = "Da-Wei Lin"
  s.platforms    = { :ios => "15.0" }
  s.source       = { :path => "." }
  s.source_files = "*.{swift,m}"
  s.dependency "React-Core"

  # Bridges in the real AegisScan Swift package from the monorepo root
  # (../../../.. relative to this podspec) -- the exact same source the
  # native iOS sample/tests use, not a copy.
  spm_dependency(
    s,
    url: File.expand_path(File.join(__dir__, "..", "..", "..", "..")),
    requirement: { kind: "upToNextMajorVersion", minimumVersion: "1.0.0" },
    products: ["AegisScan"],
  )
end
