import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, AppState, NativeModules, PermissionsAndroid, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {WebView} from 'react-native-webview';
import readerHtml from './vendor/reader-html.json';

type FileEntry = {name: string; path: string; directory: boolean};
type FileBridge = {
  getLaunchFile(): Promise<string | null>;
  name(path: string): Promise<string>;
  listFolder(path: string): Promise<FileEntry[]>;
  size(path: string): Promise<number>;
  readChunk(path: string, offset: number, count: number): Promise<string>;
  readInk(path: string): Promise<string>;
  writeInk(path: string, ink: string): Promise<void>;
  readViewState(path: string): Promise<string>;
  writeViewState(path: string, state: string): Promise<void>;
  rememberFile(path: string): Promise<void>;
  setNativeInkRegion(left: number, top: number, right: number, bottom: number, enabled: boolean): void;
  clearNativeInk(): void;
};
const bridge = NativeModules.DjvuFileBridge as FileBridge | undefined;

export default function App(): React.JSX.Element {
  const webRef = useRef<WebView>(null);
  const [file, setFile] = useState<{path: string; size: number; name: string; ink: string; viewState: string} | null>(null);
  const [message, setMessage] = useState('Choose a DjVu file to read.');
  const [generation, setGeneration] = useState(0);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [folderHistory, setFolderHistory] = useState<string[]>([]);
  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [listing, setListing] = useState(false);
  const [browserError, setBrowserError] = useState('');
  const [toolbarsVisible, setToolbarsVisible] = useState(true);
  const lastLaunchFile = useRef<string | null>(null);

  const inject = useCallback((script: string) => {
    webRef.current?.injectJavaScript(`${script}; true;`);
  }, []);

  const listFolder = useCallback(async (path: string) => {
    if (!bridge) throw new Error('The file browser is unavailable.');
    setListing(true);
    setBrowserError('');
    try { setEntries(await bridge.listFolder(path)); }
    finally { setListing(false); }
  }, []);

  const openBrowser = useCallback(async () => {
    bridge?.setNativeInkRegion(0, 0, 0, 0, false);
    bridge?.clearNativeInk();
    setBrowserOpen(true);
    setBrowserError('');
    try {
      const permission = PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;
      if (!(await PermissionsAndroid.check(permission))) {
        const result = await PermissionsAndroid.request(permission);
        if (result !== PermissionsAndroid.RESULTS.GRANTED) throw new Error('Allow file access to browse DjVu books.');
      }
      await listFolder('');
      setFolderHistory(['']);
    } catch (error) { setBrowserError(String(error)); }
  }, [listFolder]);

  const openFile = useCallback(async (path: string) => {
    try {
      if (!bridge) throw new Error('The file reader is unavailable.');
      bridge.setNativeInkRegion(0, 0, 0, 0, false);
      bridge.clearNativeInk();
      if (!path.startsWith('content://')) {
        const permission = PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;
        if (!(await PermissionsAndroid.check(permission))) {
          const result = await PermissionsAndroid.request(permission);
          if (result !== PermissionsAndroid.RESULTS.GRANTED) throw new Error('Allow file access to read DjVu books.');
        }
      }
      const name = await bridge.name(path);
      const size = await bridge.size(path);
      if (size > 268435456) throw new Error('Files over 256 MB are not supported.');
      const ink = await bridge.readInk(path);
      const viewState = await bridge.readViewState(path);
      await bridge.rememberFile(path);
      setFile({path, size, name, ink, viewState});
      setGeneration(value => value + 1);
      setToolbarsVisible(true);
      setMessage('Opening ' + name + '…');
      setBrowserError('');
      setBrowserOpen(false);
    } catch (error) {
      setMessage(String(error));
      setBrowserError(String(error));
    }
  }, []);

  useEffect(() => {
    if (!bridge) return;
    const receive = async () => {
      try {
        const path = await bridge.getLaunchFile();
        if (path && path !== lastLaunchFile.current) {
          lastLaunchFile.current = path;
          await openFile(path);
        }
      } catch (error) { setMessage(String(error)); }
    };
    void receive();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') void receive();
    });
    return () => subscription.remove();
  }, [openFile]);

  const selectEntry = useCallback(async (entry: FileEntry) => {
    if (listing) return;
    if (!entry.directory) { await openFile(entry.path); return; }
    try {
      await listFolder(entry.path);
      setFolderHistory(history => [...history, entry.path]);
    } catch (error) { setBrowserError(String(error)); }
  }, [listing, listFolder, openFile]);

  const goBack = useCallback(async () => {
    if (listing) return;
    if (folderHistory.length <= 1) { setBrowserOpen(false); return; }
    const previous = folderHistory[folderHistory.length - 2];
    try {
      await listFolder(previous);
      setFolderHistory(history => history.slice(0, -1));
    } catch (error) { setBrowserError(String(error)); }
  }, [folderHistory, listing, listFolder]);

  const onMessage = useCallback(async (event: {nativeEvent: {data: string}}) => {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (data.type === 'ready' && file) {
        inject(`window.startFile(${file.size}, ${JSON.stringify(file.name)}, ${JSON.stringify(file.ink)}, ${JSON.stringify(file.viewState)})`);
      } else if (data.type === 'read' && file && bridge) {
        const chunk = await bridge.readChunk(file.path, data.offset, 262144);
        inject(`window.receiveChunk(${JSON.stringify(chunk)})`);
      } else if (data.type === 'saveInk' && file && bridge) {
        await bridge.writeInk(file.path, data.ink);
      } else if (data.type === 'saveViewState' && file && bridge) {
        await bridge.writeViewState(file.path, JSON.stringify(data.state));
      } else if (data.type === 'error') {
        setMessage(data.message);
      } else if (data.type === 'page') {
        setMessage(`${file?.name ?? 'DjVu'} · ${data.page}/${data.count}`);
      } else if (data.type === 'renderTime') {
        console.log(`DjVu page ${data.page}: ${data.ms} ms (${data.fromCache ? 'cache' : 'decode'})`);
      } else if (data.type === 'prefetchStatus') {
        console.log(`DjVu prefetch: ${data.message}`);
      } else if (data.type === 'toolbars') {
        setToolbarsVisible(data.visible === true);
      } else if (data.type === 'penRegion' && bridge && Array.isArray(data.bounds) && data.bounds.length === 4) {
        bridge.setNativeInkRegion(data.bounds[0], data.bounds[1], data.bounds[2], data.bounds[3], data.enabled === true);
      } else if (data.type === 'clearNativeInk' && bridge) {
        bridge.clearNativeInk();
      }
    } catch (error) {
      const detail = String(error);
      setMessage(detail);
      inject(`window.readerError(${JSON.stringify(detail)})`);
    }
  }, [file, inject]);

  return <SafeAreaView style={styles.root}>
    {toolbarsVisible && <View style={styles.header}>
      <TouchableOpacity onPress={openBrowser} style={styles.button} accessibilityRole="button">
        <Text style={styles.buttonText}>{file ? 'Other file' : 'Open DjVu'}</Text>
      </TouchableOpacity>
      <Text style={styles.label} numberOfLines={1}>{message}</Text>
    </View>}
    {!!browserError && <Text style={styles.browserError}>{browserError}</Text>}
    {browserOpen ? <View style={styles.browser}>
      <View style={styles.browserHeader}>
        <TouchableOpacity onPress={goBack} style={styles.button} accessibilityRole="button"><Text style={styles.buttonText}>Back</Text></TouchableOpacity>
        <Text style={styles.browserTitle} numberOfLines={1}>{folderHistory.length <= 1 ? 'Folders' : folderHistory[folderHistory.length - 1].split('/').pop()}</Text>
      </View>
      {listing ? <ActivityIndicator size="large" color="#111" style={styles.loading} /> : <ScrollView>
        {entries.map(entry => <TouchableOpacity key={entry.path} onPress={() => selectEntry(entry)} style={styles.row} accessibilityRole="button">
          <Text style={styles.rowText} numberOfLines={2}>{entry.directory ? 'Folder  ' : 'DjVu  '}{entry.name}</Text>
        </TouchableOpacity>)}
        {entries.length === 0 && <Text style={styles.emptyFolder}>No DjVu files in this folder.</Text>}
      </ScrollView>}
    </View> : file ? <WebView
      key={generation}
      ref={webRef}
      source={{html: readerHtml}}
      onMessage={onMessage}
      javaScriptEnabled
      domStorageEnabled={false}
      originWhitelist={['*']}
      style={styles.web}
    /> : <View style={styles.empty}><Text style={styles.title}>DjVu Reader</Text><Text>Open a file to start reading.</Text></View>}
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#fff'},
  header: {height: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 8, borderBottomWidth: 1, borderColor: '#888'},
  button: {paddingHorizontal: 12, paddingVertical: 10, borderWidth: 1, borderColor: '#111', borderRadius: 4},
  buttonText: {fontSize: 17, color: '#111'},
  label: {flex: 1, marginLeft: 12, fontSize: 14, color: '#333'},
  web: {flex: 1, backgroundColor: '#fff'},
  empty: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  title: {fontSize: 24, fontWeight: 'bold', marginBottom: 10, color: '#111'},
  browser: {flex: 1, backgroundColor: '#fff'},
  browserHeader: {height: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 8, borderBottomWidth: 1, borderColor: '#888'},
  browserTitle: {fontSize: 18, marginLeft: 14, flex: 1, color: '#111'},
  row: {minHeight: 60, paddingHorizontal: 16, paddingVertical: 12, justifyContent: 'center', borderBottomWidth: 1, borderColor: '#bbb'},
  rowText: {fontSize: 18, color: '#111'},
  emptyFolder: {padding: 20, fontSize: 17, color: '#444'},
  browserError: {padding: 16, fontSize: 16, color: '#111', borderBottomWidth: 1, borderColor: '#888'},
  loading: {marginTop: 24},
});
