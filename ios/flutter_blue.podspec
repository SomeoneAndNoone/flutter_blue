Pod::Spec.new do |s|
  s.name             = 'flutter_blue'
  s.version          = '0.0.1'
  s.summary          = 'Flutter plugin for connecting and communicationg with Bluetooth Low Energy devices, on Android and iOS'
  s.description      = 'Flutter plugin for connecting and communicationg with Bluetooth Low Energy devices, on Android and iOS'
  s.homepage         = 'https://github.com/pauldemarco/flutter_blue'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Paul DeMarco' => 'paulmdemarco@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.platform = :ios, '9.0'
  s.framework = 'CoreBluetooth'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', }
end
