#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_blue.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_blue'
  s.version          = '0.0.1'
  s.summary          = 'Bluetooth Low Energy plugin for Flutter.'
  s.description      = <<-DESC
Bluetooth Low Energy plugin for Flutter.
                       DESC
  s.homepage         = 'https://github.com/pauldemarco/flutter_blue'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Paul DeMarco' => 'paulmdemarco@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*', 'gen/**/*'
  s.public_header_files = 'Classes/**/*.h', 'gen/**/*.h'
  s.dependency 'Flutter'
  s.platform = :ios, '10.0'
  s.framework = 'CoreBluetooth'

  # Fix was added for the issue: https://github.com/pauldemarco/flutter_blue/issues/292#issuecomment-510417473
  protoc = ENV['PWD'] + '/ios/Pods/!ProtoCompiler/protoc'
  objc_out = 'gen'
  proto_in = '../protos'
  s.prepare_command = <<-CMD
    mkdir -p #{objc_out}
    #{protoc} \
        --objc_out=#{objc_out} \
        --proto_path=#{proto_in} \
        #{proto_in}/*.proto
  CMD

  s.subspec 'Protos' do |ss|
    ss.source_files = 'gen/**/*.pbobjc.{h,m}'
    # Fix for the issue: https://github.com/pauldemarco/flutter_blue/issues/386#issuecomment-540876291
    # ss.header_mappings_dir = 'gen'
    ss.requires_arc = false
    ss.dependency "Protobuf", '~> 3.11.4'
  end

  # Flutter.framework does not contain a i386 slice. Only x86_64 simulators are supported.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64', 'GCC_PREPROCESSOR_DEFINITIONS' => '$(inherited) GPB_USE_PROTOBUF_FRAMEWORK_IMPORTS=1', }

end
