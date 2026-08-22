/**
 * AnimInk Supernote plugin UI.
 *
 * The drawing workspace stays native so the Supernote pen engine can keep its
 * low-latency path. React Native provides the PluginHost surface and close
 * action required by the Supernote plugin lifecycle.
 *
 * @format
 */

import React from 'react';
import {
  Pressable,
  requireNativeComponent,
  StatusBar,
  StyleSheet,
  Text,
  View,
  type ViewProps,
} from 'react-native';
import {PluginManager} from 'sn-plugin-lib';

const AnimInkNativeView = requireNativeComponent<ViewProps>('AnimInkNativeView');

function App(): React.JSX.Element {
  return (
    <View style={styles.container}>
      <StatusBar hidden />
      <AnimInkNativeView style={styles.workspace} />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close AnimInk"
        hitSlop={8}
        onPress={() => PluginManager.closePluginView()}
        style={styles.closeButton}>
        <Text style={styles.closeText}>Close ×</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  workspace: {
    flex: 1,
  },
  closeButton: {
    position: 'absolute',
    top: 6,
    right: 8,
    minWidth: 88,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#ffffff',
    borderColor: '#111111',
    borderWidth: 2,
    borderRadius: 8,
  },
  closeText: {
    color: '#111111',
    fontSize: 14,
    fontWeight: '700',
  },
});

export default App;
