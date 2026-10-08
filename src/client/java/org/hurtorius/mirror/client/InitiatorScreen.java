package org.hurtorius.mirror.client;

import com.google.gson.*;

import net.minecraft.client.gui.*;

import net.minecraft.client.gui.screens.Screen;

import net.minecraft.client.gui.components.*;

import net.minecraft.client.input.*;

import net.minecraft.network.chat.Component;

import net.minecraft.client.renderer.RenderPipelines;

import org.hurtorius.mirror.core.*;

import java.lang.reflect.*;

import java.util.*;

public final class InitiatorScreen extends Screen {

    private static final String[] TABS={"Source","Look","Placement","Motion","Display","Access","Show","Extras"};

    private static ScreenSpec copied;
    private static ScreenCoordinates copiedCoordinates;

    private Anchor original;

    private ScreenSpec draft;

    private final Set<Integer> expanded=new HashSet<>();

    private final Map<String,String> invalidValues=new HashMap<>();

    private final List<MirrorButton> fixedButtons=new ArrayList<>();

    private boolean sidePreview,powerTouched;
    private static boolean previewEnabled=true;
    private BrowserViewport pendingViewport;
    private long viewportChanged;
    public boolean previewVisible(){return previewEnabled;}

    private final DraftScreenPreview scene=new DraftScreenPreview();

    private final Set<String> invalidFields=new HashSet<>();

    private Runnable afterSave;

    private int tab=0,scroll=0,bodyTop,bodyBottom,formWidth,previewX;

    private String feedback="",pendingAction="",browserAddress="";

    private String libraryFolder="";

    private boolean saving=false,selectImported=false,importOptions=false;

    private long saveStarted;

    private boolean closeAfterSave=false,browserFocus=false;

    private EngineProcess engine;

    private final List<Row> rows=new ArrayList<>();

    private record Row(String label,String field,List<AbstractWidget> widgets,int kind){}

    private static final String[][] FIELDS={

        {"live","name","source","url","loop","slideSeconds","subtitle"},

        {"width","height","shape","fit","browserAutoSize","browserWidth","browserHeight","edge","color","edgeWidth","glow","opacity","contrast","saturation","scanlines","doubleSided","backMirrored","shadow","reflection"},

        {"facing","target","followPlayer","x","y","z","yaw","pitch","roll","orbitRadius","orbitSeconds"},

        {"entrance","transition","easeSeconds","floatAmount","swayDegrees","pathLoop"},

        {"brightness","volume","emissive","glow","reflection","soundRange","positional","wideStereo","duckMusic"},

        {"audience","viewRange","group","allowSharing","locked","allowedSites","blockedSites"},

        {"triggersArmed","trigger","areaRadius","resetShow"},

        {"redstone","link","projector","projectionDepth","wallColumns","wallRows","wallColumn","wallRow"}

    };

    private static final Map<String,String> LABELS=Map.ofEntries(

        Map.entry("browserAutoSize","Match screen"),Map.entry("browserWidth","Browser width"),Map.entry("browserHeight","Browser height"),Map.entry("emissive","Self-lit"),Map.entry("glow","World light"),Map.entry("reflection","Reflection"),Map.entry("width","Width"),Map.entry("height","Height"),Map.entry("source","Content"),Map.entry("fit","Picture fit"),Map.entry("shape","Shape"),Map.entry("edge","Frame"),Map.entry("volume","Volume"),Map.entry("live","Screen enabled"),Map.entry("triggersArmed","Allow world triggers"),Map.entry("text","Screen text"),Map.entry("name","Screen name"),Map.entry("url","Website"),Map.entry("slideSeconds","Seconds per page"),Map.entry("subtitle","Subtitle text"),

        Map.entry("edgeWidth","Edge thickness"),Map.entry("doubleSided","Picture on back"),Map.entry("target","Specific player"),Map.entry("followPlayer","Move with this player"),

        Map.entry("x","Center X"),Map.entry("y","Center Y"),Map.entry("z","Center Z"),Map.entry("brightness","Brightness"),Map.entry("yaw","Direction"),Map.entry("pitch","Tilt"),Map.entry("roll","Roll"),

        Map.entry("orbitRadius","Orbit radius, blocks"),Map.entry("orbitSeconds","Seconds per orbit"),Map.entry("easeSeconds","Seconds to glide into a change"),Map.entry("floatAmount","Idle float, blocks"),Map.entry("swayDegrees","Idle sway, degrees"),

        Map.entry("soundRange","Sound range"),Map.entry("positional","Positional sound"),Map.entry("wideStereo","Stereo"),Map.entry("duckMusic","Stop game music"),

        Map.entry("viewRange","View range"),Map.entry("group","Saved audience group"),Map.entry("allowSharing","Allow sharing requests"),Map.entry("locked","Lock to this operator"),

        Map.entry("allowedSites","Allowed domains, separated by commas"),Map.entry("blockedSites","Blocked domains, separated by commas"),Map.entry("areaRadius","Area-trigger radius, blocks"),Map.entry("resetShow","Reset when the show finishes"),

        Map.entry("pathLoop","Loop the flight path"),Map.entry("link","Follow another Initiator's content"),Map.entry("projectionDepth","Project up to this many blocks"),

        Map.entry("wallColumns","Screen-wall columns"),Map.entry("wallRows","Screen-wall rows"),Map.entry("wallColumn","This column, starting at 0"),Map.entry("wallRow","This row, starting at 0")

    );

    public InitiatorScreen(Anchor anchor){super(Component.literal("Mirror Initiator"));original=anchor;draft=anchor.spec.copy();browserAddress=MirrorClient.browserAddress(anchor.id,draft.url);if(MirrorClient.hasBrowser(anchor.id))draft.url=browserAddress;}

    public String anchorId(){return original.id;}

    public String drawingId(){return original.boardMedia;}

    public ScreenSpec preview(){try{draft.validate();return draft;}catch(IllegalArgumentException e){return original.spec;}}

    public PreviewConfig previewConfig(){
        var spec=preview();var config=PreviewConfig.from(spec);
        if(minecraft!=null&&minecraft.level!=null){var pose=ScreenPose.sample(original,spec,minecraft.level,MirrorRenderer.viewPosition(),System.currentTimeMillis()+MirrorClient.serverOffset,MirrorClient.preferences.reducedMotion);config.offsetX=(float)(pose.x()-original.x-.5);config.offsetY=(float)(pose.y()-original.y);config.offsetZ=(float)(pose.z()-original.z-.5);config.yaw=(float)pose.yaw();config.pitch=(float)pose.pitch();config.roll=(float)pose.roll();}
        if(!spec.emissive&&minecraft!=null&&minecraft.level!=null)config.light=net.minecraft.client.renderer.LevelRenderer.getLightColor(minecraft.level,net.minecraft.core.BlockPos.containing(original.x+.5+config.offsetX,original.y+config.offsetY,original.z+.5+config.offsetZ));
        return config;
    }

    public void message(String text){feedback=text;}

    public void saveFailed(){saving=false;pendingAction="";closeAfterSave=false;afterSave=null;layoutRows();}

    public void groupsUpdated(){if(feedback.equals("Saving audience group…"))feedback="Audience group saved";}

    public String browserAddress(){return MirrorClient.browserAddress(original.id,browserAddress);}
    public void syncBrowserAddress(){draft.url=browserAddress();}
    public void browseAt(String address){draft.url=address;openBrowser(false,true,false);}

    public double previewVolume(){return draft.volume;}
    public ScreenSpec.Source contentSource(){return draft.source;}
    public boolean isSaving(){return saving;}
    public void toggleBrowserSound(){if(saving)return;draft.volume=draft.volume>0?0:.7;if(MirrorClient.isBrowserPublished(original.id))save("",false);}

    public boolean importOptions(){return importOptions;}

    public void importCancelled(){selectImported=false;feedback="Import cancelled";}

    public void imported(WorldStore.Media media){if(media.kind().equals("subtitle"))draft.subtitle=media.id();else{if(!draft.playlist.contains(media.id()))draft.playlist.add(media.id());if(draft.source==ScreenSpec.Source.MEDIA)draft.link="";if(selectImported){draft.source=ScreenSpec.Source.MEDIA;draft.item=draft.playlist.indexOf(media.id());draft.live=true;powerTouched=true;selectImported=false;}}feedback="Imported "+media.name();rebuildKeepingFocus();}

    private void rebuildKeepingFocus(){EditBox input=getFocused() instanceof EditBox box?box:null;String label=input==null?"":input.getMessage().getString(),value=input==null?"":input.getValue();int cursor=input==null?0:input.getCursorPosition();rebuildWidgets();if(input!=null)for(var child:children())if(child instanceof EditBox box&&box.getMessage().getString().equals(label)){box.setValue(value);box.setCursorPosition(Math.min(cursor,value.length()));setInitialFocus(box);break;}}

    public void saved(Anchor anchor){if(!saving||!anchor.id.equals(original.id))return;saving=false;powerTouched=false;original=anchor;draft=anchor.spec.copy();feedback="Saved";if(!pendingAction.isEmpty()){String action=pendingAction;pendingAction="";MirrorClient.send(action,"id",original.id);}if(afterSave!=null){Runnable next=afterSave;afterSave=null;rebuildWidgets();next.run();}else if(closeAfterSave){closeEditor();}else rebuildWidgets();}

    public void control(Anchor anchor){if(anchor.id.equals(original.id)&&anchor.revision>=original.revision){boolean inheritPower=draft.live==original.spec.live;original=anchor;if(inheritPower)draft.live=anchor.spec.live;}}

    public boolean sourceSaved(ScreenSpec.Source source){return !saving&&original.spec.source==source;}

    public void publishing(String epoch){feedback="";}
    public void finishBrowserShow(){saving=false;closeEditor();}

    public void engineEvent(JsonObject m){String type=m.get("type").getAsString();if(type.equals("ADDRESS"))browserAddress=m.get("url").getAsString();if(type.equals("MEDIA_INFO"))feedback="Media ready";if(type.equals("SOURCE_READY"))feedback="";}

    protected void init(){

        rows.clear();fixedButtons.clear();scene.cancelDrag();sidePreview=previewEnabled;

        formWidth=sidePreview?(width-34)*57/100:width-20;previewX=formWidth+22;bodyTop=56;bodyBottom=height-49;

        var power=fixed(new MirrorButton(width-178,7,82,powerOn()?"Screen: On":"Screen: Off",this::toggleScreen));

        power.setTooltip(Tooltip.create(Component.literal("Show or hide this screen now. This saves the current settings. New devices start off.")));

        fixed(new MirrorButton(width-92,7,82,previewEnabled?"Preview: On":"Preview: Off",()->{previewEnabled=!previewEnabled;rebuildWidgets();}));

        int[] tabs={0,1,2,4,5,7};String[] names={"Content","Look","Place","Display","Access","More"};int tw=(width-20)/tabs.length;

        for(int i=0;i<tabs.length;i++){int target=tabs[i];var button=new MirrorButton(10+i*tw,32,tw-3,names[i],()->{tab=target;scroll=0;feedback="";rebuildWidgets();});button.active=tab!=target;addRenderableWidget(button);}

        if(sidePreview)for(var button:scene.controls(previewX+4,bodyTop+16,width-previewX-18))addRenderableWidget(button);

        switch(tab){

            case 0->{sourceChoices();sourceRows();}

            case 1->{section("Size and shape");addField("width");addField("height");action("Aspect ratio…",()->choices("Aspect ratio",List.of(new ChoiceScreen.Option<>(16/9.0,"Widescreen · 16:9","Video and presentations"),new ChoiceScreen.Option<>(4/3.0,"Classic · 4:3","Older video and slides"),new ChoiceScreen.Option<>(1.0,"Square · 1:1","Square pictures"),new ChoiceScreen.Option<>(9/16.0,"Portrait · 9:16","Tall pictures")),0.0,ratio->{draft.height=Math.min(36,Math.max(.25,draft.width/ratio));}));addField("shape");if(draft.shape==ScreenSpec.Shape.CUSTOM)action("Edit custom outline…",()->minecraft.setScreen(new OutlineScreen(this,draft)));addField("fit");action("Sides: "+(!draft.doubleSided?"Front only":draft.backMirrored?"Both mirrored":"Both readable"),()->choices("Screen sides",List.of(new ChoiceScreen.Option<>(0,"Front only","One visible face. Choose a Facing option to turn it toward viewers."),new ChoiceScreen.Option<>(1,"Both readable","Pictures and text read normally from either side."),new ChoiceScreen.Option<>(2,"Both mirrored","The back reads as the reverse of the front.")),!draft.doubleSided?0:draft.backMirrored?2:1,side->{draft.doubleSided=side!=0;draft.backMirrored=side==2;}));if(draft.source==ScreenSpec.Source.WEB){section("Browser viewport");addField("browserAutoSize");if(!draft.browserAutoSize)fields("browserWidth","browserHeight");note(draft.browserAutoSize?"The website layout follows the screen width and height.":"CSS pixels. Change both dimensions to set a custom browser aspect ratio.");}addField("edge");if(draft.edge!=ScreenSpec.Edge.NONE){addField("color");addField("edgeWidth");}advanced("More picture options",()->fields("opacity","contrast","saturation","scanlines","shadow"));}

            case 2->{section(draft.path.isEmpty()?(draft.followPlayer?"Position · offset from followed player":draft.facing==ScreenSpec.Facing.ORBIT?"Orbit center · world coordinates":"Screen center · world coordinates"):"Position · flight path");if(draft.path.isEmpty())fields("x","y","z");else{note("The flight path controls the screen center. Edit its stops using world coordinates.");action("Edit flight path…",()->openTab(3));}addField("facing");if(draft.facing==ScreenSpec.Facing.FIXED||draft.facing==ScreenSpec.Facing.MOUNTED)addField("yaw");if(draft.facing==ScreenSpec.Facing.SPECIFIC_PLAYER||draft.followPlayer)addField("target");if(draft.facing==ScreenSpec.Facing.ORBIT)fields("orbitRadius","orbitSeconds");action(draft.path.isEmpty()?"Place in front of me":"Clear flight path and place in front of me",()->{var point=minecraft.player.getEyePosition().add(minecraft.player.getLookAngle().scale(5));draft.x=point.x-original.x-.5;draft.y=point.y-original.y;draft.z=point.z-original.z-.5;draft.yaw=((minecraft.player.getYRot()+180)%360+360)%360;draft.pitch=draft.roll=0;draft.facing=ScreenSpec.Facing.FIXED;draft.followPlayer=false;draft.path.clear();rebuildWidgets();});advanced("More placement options",()->{fields("pitch","roll","followPlayer");if(draft.followPlayer&&draft.facing!=ScreenSpec.Facing.SPECIFIC_PLAYER)addField("target");action(draft.path.isEmpty()?"Snap center to whole coordinates":"Snap flight stops to whole coordinates",()->{var origin=coordinates();draft.x=origin.snapped("x",draft.x);draft.y=origin.snapped("y",draft.y);draft.z=origin.snapped("z",draft.z);var pathOrigin=ScreenCoordinates.at(original);draft.path=new ArrayList<>(draft.path.stream().map(p->new ScreenSpec.Waypoint(pathOrigin.snapped("x",p.x()),pathOrigin.snapped("y",p.y()),pathOrigin.snapped("z",p.z()),p.yaw(),p.pitch(),p.seconds())).toList());rebuildWidgets();});action("Copy look and placement",()->{copied=draft.copy();copiedCoordinates=ScreenCoordinates.at(original);feedback="Layout copied";});action("Paste layout",this::pasteLayout);});}

            case 4->{if(draft.source==ScreenSpec.Source.WEB)action("Browser motion: "+draft.browserFps+" FPS",()->choices("Browser motion",List.of(new ChoiceScreen.Option<>(60,"Smooth · 60 FPS","Smooth scrolling and high-frame-rate video; uses more processing."),new ChoiceScreen.Option<>(30,"Balanced · 30 FPS","Lower processing cost.")),draft.browserFps,fps->draft.browserFps=fps));section("Brightness and sound");fields("brightness","volume");section("Lighting");fields("emissive","glow","reflection");section("Sound");fields("soundRange","positional","wideStereo","duckMusic");}

            case 5->{fields("audience","viewRange");if(draft.audience==ScreenSpec.Audience.SELECTED){addField("group");action("Choose audience players…",()->players(false));action("Save this audience as a group",()->{String name=draft.group.isBlank()?draft.name:draft.group;MirrorClient.send("GROUP_SAVE","name",name,"players",draft.viewers);draft.group=name;feedback="Saving audience group…";});}fields("allowSharing","locked");advanced("More access options",()->{action("Choose who may share…",()->players(true));fields("allowedSites","blockedSites");});}

            case 3->{section("Motion");fields("entrance","transition","easeSeconds","floatAmount","swayDegrees","pathLoop");action("Add this position to the flight path",()->{if(draft.path.size()>=64){feedback="A path can have up to 64 stops";return;}draft.path.add(new ScreenSpec.Waypoint(draft.x,draft.y,draft.z,draft.yaw,draft.pitch,5));rebuildWidgets();});for(int i=0;i<draft.path.size();i++){int index=i;action("Flight stop "+(i+1)+" · "+draft.path.get(i).seconds()+" seconds",()->minecraft.setScreen(new WaypointEditorScreen(this,draft,index,ScreenCoordinates.at(original))));}action("Clear flight path",()->{draft.path.clear();rebuildWidgets();});}

            case 6->{section("Shows and triggers");fields("triggersArmed","trigger");if(draft.trigger==ScreenSpec.Trigger.AREA)addField("areaRadius");addField("resetShow");showRows();}

            case 7->{section("Tools");action("Reports…",()->minecraft.setScreen(new ReportsScreen(this)));action("Motion and flight paths…",()->openTab(3));action("Shows and world triggers…",()->openTab(6));action("Your display settings…",()->minecraft.setScreen(new ViewerSettingsScreen(this)));action("World media library…",()->minecraft.setScreen(new LibraryScreen(this)));if(MirrorClient.serverOwner)action("World controls…",()->minecraft.setScreen(new ServerSettingsScreen(this)));action("OBS content output…",()->CameraOutput.open(original.id));addField("name");advanced("Linking, projector and redstone",()->{fields("redstone","link","projector");if(draft.projector)addField("projectionDepth");section("Screen wall");fields("wallColumns","wallRows","wallColumn","wallRow");});}

        }

        var done=fixed(new MirrorButton(width/2-70,height-27,140,"Done",this::finishEditing));done.setTooltip(Tooltip.create(Component.literal("Save settings and close. Esc closes without saving.")));

        layoutRows();

    }

    private MirrorButton fixed(MirrorButton b){fixedButtons.add(b);return addRenderableWidget(b);}

    private boolean playingNow(){var view=MirrorClient.views.get(original.id);return view!=null&&draft.source==ScreenSpec.Source.MEDIA&&draft.currentMedia().equals(view.anchor().spec.currentMedia())&&view.anchor().playing;}

    private boolean visibleNow(){var view=MirrorClient.views.get(original.id);return view!=null?view.anchor().spec.live:original.spec.live;}


    private boolean unchanged(){return !powerTouched&&invalidFields.isEmpty()&&ScreenSpec.JSON.toJson(draft).equals(ScreenSpec.JSON.toJson(original.spec));}

    private void finishEditing(){
        if(unchanged()){closeEditor();return;}

        if(!draft.live){save("",true);return;}
        if(MirrorClient.emergency){feedback="Turn off the world emergency stop before enabling this screen.";return;}

        if(draft.source==ScreenSpec.Source.WEB){if(MirrorClient.isBrowserPublished(original.id)){openBrowser(false,false,true);}else openBrowser(true,false,true);return;}

        if(draft.source==ScreenSpec.Source.SHARE&&!visibleNow()){feedback="Choose a window or player to share first.";openTab(0);return;}

        if(draft.source==ScreenSpec.Source.MEDIA){if(draft.playlist.isEmpty()){feedback="Choose a file or import one first.";openTab(0);return;}boolean start=!visibleNow()||!draft.currentMedia().equals(original.spec.currentMedia());draft.live=true;save(start?"PLAY":"",true);return;}

        draft.live=true;save("",true);

    }

    public void playSelection(boolean close){if(draft.playlist.isEmpty()){feedback="Choose a file first.";return;}draft.source=ScreenSpec.Source.MEDIA;draft.live=true;save("PLAY",close);}

    private void sourceChoices(){

        ScreenSpec.Source[] sources={ScreenSpec.Source.WEB,ScreenSpec.Source.MEDIA,ScreenSpec.Source.TEXT,ScreenSpec.Source.BLANK,ScreenSpec.Source.SHARE,ScreenSpec.Source.WHITEBOARD};

        String[] names={"Web","Media","Text","Blank","Share","Draw"};

        for(int row=0;row<1;row++){List<AbstractWidget> buttons=new ArrayList<>();for(int i=0;i<sources.length;i++){ScreenSpec.Source selected=sources[i];var button=addRenderableWidget(new MirrorButton(0,0,80,names[i],()->{cancelPendingBrowser();draft.source=selected;draft.live=true;powerTouched=true;scroll=0;feedback="";rebuildWidgets();}));buttons.add(button);}rows.add(new Row("","",List.copyOf(buttons),4));}

    }

    private boolean powerOn(){return powerTouched?draft.live:visibleNow();}

    private void toggleScreen(){draft.live=!powerOn();powerTouched=true;if(!draft.live){draft.triggersArmed=false;cancelPendingBrowser();}feedback="";}

    private void cancelPendingBrowser(){if(MirrorClient.browserShowPending(original.id)){MirrorClient.privateBrowser(original.id);MirrorClient.send("DISMISS","id",original.id);feedback="";}}

    public void tick(){
        if(previewEnabled&&draft.source==ScreenSpec.Source.WEB&&MirrorClient.hasBrowser(original.id)&&!MirrorClient.isBrowserPublished(original.id)&&invalidFields.isEmpty()){
            var spec=preview();var size=BrowserViewport.of(spec);long now=System.nanoTime();
            if(!size.equals(pendingViewport)){pendingViewport=size;viewportChanged=now;}
            else if(now-viewportChanged>200_000_000)MirrorClient.resizePrivatePreview(original.id,spec);
        }

        for(Row row:rows)for(var widget:row.widgets)if(widget instanceof MirrorButton button&&(button.getMessage().getString().equals("Play")||button.getMessage().getString().equals("Pause")))button.setMessage(Component.literal(playingNow()?"Pause":"Play"));

        if(!fixedButtons.isEmpty()){fixedButtons.getFirst().setMessage(Component.literal(powerOn()?"Screen: On":"Screen: Off"));fixedButtons.getLast().setMessage(Component.literal("Done"));}

        if(saving&&System.currentTimeMillis()-saveStarted>10000){saveFailed();feedback="No save response. Your edits are still here; reconnect or try Done again.";}

        for(var button:fixedButtons)button.active=!saving&&(!button.getMessage().getString().equals("Done")||!MirrorClient.browserShowPending(original.id));

    }

    private void openTab(int value){tab=value;scroll=0;rebuildWidgets();}

    private void fields(String...names){for(String name:names)addField(name);}

    private void section(String label){rows.add(new Row(label,"",List.of(),2));}

    private void note(String label){rows.add(new Row(label,"",List.of(),3));}

    private void advanced(String title,Runnable content){action((expanded.contains(tab)?"− ":"+ ")+title,()->{if(!expanded.add(tab))expanded.remove(tab);rebuildWidgets();});if(expanded.contains(tab))content.run();}

    private <T> void choices(String title,List<ChoiceScreen.Option<T>> options,T current,java.util.function.Consumer<T> setter){minecraft.setScreen(new ChoiceScreen<>(this,title,options,current,setter));}

    private void players(boolean sharing){if(!sharing)draft.group="";minecraft.setScreen(new PlayerSelectionScreen(this,sharing?draft.sharePlayers:draft.viewers,sharing?"Who may be asked to share":"Audience players"));}

    public ScreenCoordinates coordinates(){return draft.followPlayer?ScreenCoordinates.followedPlayer():ScreenCoordinates.at(original);}
    private void pasteLayout(){
        if(copied==null){feedback="Copy a layout first";return;}
        try{
            ScreenSpec next=draft.copy();
            for(int category=1;category<=3;category++)for(String name:FIELDS[category]){Field f=ScreenSpec.class.getField(name);f.set(next,f.get(copied));}
            var pathOrigin=ScreenCoordinates.at(original);next.path=new ArrayList<>(copied.path.stream().map(p->new ScreenSpec.Waypoint(pathOrigin.stored("x",copiedCoordinates.display("x",p.x())),pathOrigin.stored("y",copiedCoordinates.display("y",p.y())),pathOrigin.stored("z",copiedCoordinates.display("z",p.z())),p.yaw(),p.pitch(),p.seconds())).toList());next.outline=new ArrayList<>(copied.outline);
            if(!next.followPlayer){var target=ScreenCoordinates.at(original);next.x=target.stored("x",copiedCoordinates.display("x",copied.x));next.y=target.stored("y",copiedCoordinates.display("y",copied.y));next.z=target.stored("z",copiedCoordinates.display("z",copied.z));}
            next.validate();draft=next;invalidFields.clear();invalidValues.clear();feedback="Layout pasted";rebuildWidgets();
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}catch(IllegalArgumentException e){feedback=e.getMessage();}
    }

    private void addField(String name){try{

        Field field=ScreenSpec.class.getField(name);Object value=field.get(draft);ScreenCoordinates origin=coordinates();String label=ScreenCoordinates.isAxis(name)&&draft.followPlayer?"Follow offset "+name.toUpperCase(Locale.ROOT):LABELS.getOrDefault(name,capitalize(name));List<AbstractWidget> controls=new ArrayList<>();

        if(Set.of("volume","glow","opacity").contains(name)){

            double min=name.equals("opacity")?.05:0;controls.add(new AbstractSliderButton(0,0,100,20,Component.literal(Math.round(((Number)value).doubleValue()*100)+"%"),(((Number)value).doubleValue()-min)/(1-min)){

                protected void updateMessage(){setMessage(Component.literal(Math.round((min+value*(1-min))*100)+"%"));}

                protected void applyValue(){try{field.setDouble(draft,Math.rint((min+value*(1-min))*100)/100);feedback="";}catch(IllegalAccessException e){throw new IllegalStateException(e);}}

            });

        }else if(value instanceof Boolean flag)controls.add(new MirrorButton(0,0,100,flag?"On":"Off",()->{try{field.setBoolean(draft,!field.getBoolean(draft));if(name.equals("browserAutoSize")&&draft.browserAutoSize){for(String input:List.of("browserWidth","browserHeight")){invalidFields.remove(input);invalidValues.remove(input);}feedback="";}rebuildWidgets();}catch(IllegalAccessException e){throw new IllegalStateException(e);}}));

        else if(value instanceof Enum<?> choice)controls.add(new MirrorButton(0,0,100,pretty(choice.name())+"…",()->{List<ChoiceScreen.Option<Object>> options=new ArrayList<>();for(Object option:field.getType().getEnumConstants()){String key=((Enum<?>)option).name();options.add(new ChoiceScreen.Option<>(option,pretty(key),optionHelp(key)));}choices(label,options,value,selected->{try{field.set(draft,selected);if(name.equals("source")){draft.link="";scroll=0;}}catch(IllegalAccessException e){throw new IllegalStateException(e);}});}));

        else if(Set.of("target","group","link").contains(name)){

            String id=value.toString(),shown=id.isEmpty()?"None":name.equals("target")?MirrorClient.players.getOrDefault(id,"Unavailable player"):name.equals("link")?MirrorClient.views.containsKey(id)?MirrorClient.views.get(id).anchor().spec.name:"Unavailable screen":id;

            controls.add(new MirrorButton(0,0,100,shown+"…",()->{List<ChoiceScreen.Option<String>> options=new ArrayList<>();options.add(new ChoiceScreen.Option<>("","None","Clear this selection"));Collection<String> ids=name.equals("group")?MirrorClient.groups.keySet():name.equals("target")?MirrorClient.players.keySet():MirrorClient.views.keySet();for(String key:ids)options.add(new ChoiceScreen.Option<>(key,name.equals("target")?MirrorClient.players.get(key):name.equals("link")?MirrorClient.views.get(key).anchor().spec.name:key,"Select "+name));choices(label,options,id,selected->{try{field.set(draft,selected);if(name.equals("group")&&!selected.isEmpty()){draft.viewers=new ArrayList<>(MirrorClient.groups.get(selected));draft.audience=ScreenSpec.Audience.SELECTED;}}catch(IllegalAccessException e){throw new IllegalStateException(e);}});}));

        }else{

            EditBox box=new EditBox(font,0,0,100,20,Component.literal(label));box.setMaxLength(name.equals("name")?64:value instanceof List?2048:name.equals("url")||name.equals("subtitle")||name.equals("text")?2048:128);

            String text=value instanceof List<?> list?String.join(",",list.stream().map(Object::toString).toList()):name.equals("color")?String.format("%06x",value):value instanceof Double number?number(ScreenCoordinates.isAxis(name)?origin.display(name,number):number):value.toString();box.setValue(invalidValues.getOrDefault(name,text));

            if(name.equals("url")){box.setHint(Component.literal("https://…"));box.setCursorPosition(0);}if(name.equals("text"))box.setHint(Component.literal("Your message"));

            box.setResponder(input->{try{if(field.getType()==double.class){double n=Double.parseDouble(input);if(!Double.isFinite(n))throw new NumberFormatException();if(ScreenCoordinates.isAxis(name))n=origin.stored(name,n);field.setDouble(draft,n);}else if(field.getType()==int.class)field.setInt(draft,name.equals("color")?Integer.parseInt(input.replace("#",""),16):Integer.parseInt(input));else if(value instanceof List)field.set(draft,new ArrayList<>(Arrays.stream(input.split(",")).map(String::trim).filter(v->!v.isEmpty()).toList()));else field.set(draft,input);if(value instanceof Number&&!name.equals("color")){double[] r=range(name);ScreenSpec.range(((Number)field.get(draft)).doubleValue(),r[0],r[1],label);}invalidFields.remove(name);invalidValues.remove(name);feedback="";}catch(Exception e){invalidFields.add(name);invalidValues.put(name,input);feedback="Check "+label.toLowerCase(Locale.ROOT);}});

            controls.add(box);

            if((value instanceof Number)&&!name.equals("color")){controls.add(new MirrorButton(0,0,20,"−",()->adjust(field,name,-1)));controls.add(new MirrorButton(0,0,20,"+",()->adjust(field,name,1)));}

        }

        for(AbstractWidget control:controls){control.setTooltip(Tooltip.create(Component.literal(help(name))));addRenderableWidget(control);}rows.add(new Row(label,name,List.copyOf(controls),0));

    }catch(ReflectiveOperationException e){throw new IllegalStateException("Mirror field missing: "+name,e);}}

    private void adjust(Field field,String name,int direction){try{double[] range=range(name);double n=Math.clamp(((Number)field.get(draft)).doubleValue()+direction*range[2],range[0],range[1]);n=Math.rint(n*10000)/10000;if(field.getType()==int.class)field.setInt(draft,(int)n);else field.setDouble(draft,n);if(name.equals("wallColumns"))draft.wallColumn=Math.min(draft.wallColumn,draft.wallColumns-1);if(name.equals("wallRows"))draft.wallRow=Math.min(draft.wallRow,draft.wallRows-1);invalidFields.remove(name);invalidValues.remove(name);rebuildWidgets();}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}

    private static double[] range(String n){return switch(n){case "browserWidth","browserHeight"->new double[]{16,1920,64};case "width"->new double[]{.25,64,.25};case "height"->new double[]{.25,36,.25};case "x","y","z"->new double[]{-128,128,.25};case "yaw"->new double[]{-360,360,15};case "pitch"->new double[]{-90,90,5};case "roll"->new double[]{-180,180,5};case "volume","glow"->new double[]{0,1,.1};case "opacity"->new double[]{.05,1,.05};case "brightness"->new double[]{0,2,.1};case "contrast","saturation"->new double[]{0,2,.1};case "edgeWidth"->new double[]{0,.5,.01};case "floatAmount"->new double[]{0,2,.05};case "swayDegrees"->new double[]{0,15,1};case "easeSeconds"->new double[]{0,10,.1};case "wallColumns","wallRows"->new double[]{1,8,1};case "wallColumn","wallRow"->new double[]{0,7,1};case "projectionDepth"->new double[]{.5,64,.5};case "soundRange","viewRange"->new double[]{1,128,1};case "orbitRadius"->new double[]{0,64,.5};case "areaRadius"->new double[]{1,64,1};default->new double[]{1,3600,1};};}

    private static String number(double n){return java.math.BigDecimal.valueOf(n).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}

    private String help(String field){return switch(field){case "browserAutoSize"->"Reflow the web page to match the physical screen aspect ratio. Turn off for custom browser pixels.";case "browserWidth","browserHeight"->"Viewport in CSS pixels, up to 1920 per side and 2,073,600 pixels total.";case "emissive"->"On: stays lit in darkness. Off: responds to world lighting. Shader bloom depends on your shader settings.";case "glow"->"Light emitted into the world, from 0 (none) to 1.";case "width","height"->"Measured in blocks. Type a value, or use − / + for quarter-block steps.";case "x","y","z"->draft.followPlayer?"Offset from the followed player, in blocks.":"World coordinate of the screen center. Decimals are allowed; keep the center within 128 blocks per axis of the Initiator.";case "brightness"->"0 is black, 1 is normal, 2 is brighter. Applies to pictures and screen text.";case "volume"->"Drag to change this screen's volume. Minecraft's Master and Jukebox volume also apply.";case "opacity"->"Drag the slider to adjust this setting.";case "source"->"Choose what the screen shows. Blank is plain black.";case "color"->"Frame color as six hexadecimal digits, for example 71e8ed.";case "url"->"An http or https address. Browsing starts private.";default->LABELS.getOrDefault(field,capitalize(field))+" · changes take effect when you press Done.";};}

    private static String optionHelp(String key){return switch(key){case "BLANK"->"Plain black, with no picture or message";case "MEDIA"->"Images, video, music and PDF files saved in this world";case "WEB"->"Browse privately, then explicitly share the browser view";case "SHARE"->"Ask a player to choose a window or display to share";case "WHITEBOARD"->"Draw on a shared canvas";case "TEXT"->"A message rendered in Minecraft's font";case "EACH_VIEWER"->"Each person sees the screen face them";case "BILLBOARD"->"Face each viewer while staying upright";case "FIXED"->"Keep the angles you choose";default->pretty(key);};}

    private void action(String label,Runnable action){var button=addRenderableWidget(new MirrorButton(0,0,100,label,action));rows.add(new Row("","",List.of(button),1));}

    public void pickFile(){importOptions=false;MirrorClient.filePicker(false).send("PICK_FILE");}

    private void importMedia(){selectImported=true;pickFile();}

    private void pickWithOptions(){selectImported=false;importOptions=true;MirrorClient.filePicker(true).send("PICK_FILE");}

    private void sourceRows(){

        switch(draft.source){

            case BLANK->{}

            case TEXT->addField("text");

            case WEB->{addField("url");action("Browse",()->openBrowser(false,true,false));}

            case SHARE->{action("Share my screen",()->{draft.live=true;saveThen("",()->MirrorClient.send("REQUEST_SHARE","id",original.id,"player",minecraft.player.getUUID().toString()));});action("Ask another player…",()->{draft.live=true;saveThen("",()->choices("Ask a player to share",MirrorClient.players.entrySet().stream().map(e->new ChoiceScreen.Option<>(e.getKey(),e.getValue(),"They choose whether to share, and which window or display.")).toList(),"",player->MirrorClient.send("REQUEST_SHARE","id",original.id,"player",player)));});}

            case WHITEBOARD->action("Open whiteboard…",()->{draft.live=true;saveThen("WHITEBOARD",()->minecraft.setScreen(new WhiteboardScreen(this,original.id)));});

            case MEDIA->{action("Choose media · "+draft.playlist.size()+" selected",()->minecraft.setScreen(new MediaPickerScreen(this,draft)));action("Import…",this::importMedia);if(!draft.playlist.isEmpty()){action("Playlist and order…",()->minecraft.setScreen(new PlaylistScreen(this,draft)));action(playingNow()?"Pause":"Play",()->{boolean playing=playingNow();if(!playing)draft.live=true;save(playing?"PAUSE":"PLAY",false);});action("Jump to a time…",()->saveThen("",()->minecraft.setScreen(new SeekScreen(this,original.id))));}advanced("Playback options",()->{fields("loop","slideSeconds","subtitle");action("Import with options…",this::pickWithOptions);action("Reload media",()->MirrorClient.retryMedia(original.id));action("Restart from beginning",()->saveThen("",()->MirrorClient.send("SEEK","id",original.id,"seconds",0)));});}

        }

    }

    private void showRows(){

        action("Redstone film choices",()->minecraft.setScreen(new BranchScreen(this,draft)));

        for(int i=0;i<draft.show.size();i++){int index=i;ScreenSpec.Cue c=draft.show.get(i);action((i+1)+" · "+CueEditorScreen.label(c.action())+" · wait "+number(c.seconds())+"s",()->minecraft.setScreen(new CueEditorScreen(this,draft,index,ScreenCoordinates.at(original))));}

        action("Add a show cue",()->minecraft.setScreen(new CueEditorScreen(this,draft,-1,ScreenCoordinates.at(original))));

        action("Run this show",()->save("SHOW",false));

    }

    private int rowHeight(Row row){return row.kind==2?18:row.kind==3?Math.max(20,font.split(Component.literal(row.label),formWidth-12).size()*10+8):26;}

    private int contentHeight(){return rows.stream().mapToInt(this::rowHeight).sum();}

    private void layoutRows(){

        scroll=Math.clamp(scroll,0,Math.max(0,contentHeight()-(bodyBottom-bodyTop)));int offset=0,labelWidth=Math.min(138,Math.max(68,formWidth*40/100));

        for(Row row:rows){int y=bodyTop+offset-scroll,h=rowHeight(row);for(var widget:row.widgets){widget.active=!saving;widget.visible=y>=bodyTop&&y+h<=bodyBottom;widget.setY(y+3);}

            if(row.kind==0){int x=10+labelWidth,w=formWidth-labelWidth;if(row.widgets.size()==3){row.widgets.get(0).setX(x);row.widgets.get(0).setWidth(w-48);for(int i=1;i<3;i++){row.widgets.get(i).setX(x+w-48+(i-1)*24);row.widgets.get(i).setWidth(22);}}else{row.widgets.getFirst().setX(x);row.widgets.getFirst().setWidth(w);}}

            else if(row.kind==1){row.widgets.getFirst().setX(10);row.widgets.getFirst().setWidth(formWidth);}

            else if(row.kind==4){int cell=formWidth/row.widgets.size();String selected=switch(draft.source){case WEB->"Web";case MEDIA->"Media";case TEXT->"Text";case BLANK->"Blank";case SHARE->"Share";case WHITEBOARD->"Draw";};for(int i=0;i<row.widgets.size();i++){var button=row.widgets.get(i);button.setX(10+i*cell);button.setWidth(cell-3);button.active=!saving&&!button.getMessage().getString().equals(selected);}}

            offset+=h;

        }

        for(var button:fixedButtons)button.active=!saving&&(!button.getMessage().getString().equals("Done")||!MirrorClient.browserShowPending(original.id));

    }


    private void save(String action,boolean close){if(saving){feedback="Wait for the current save to finish.";return;}try{if(!MirrorClient.connected())throw new IllegalArgumentException("Not connected to Mirror. Your edits are still here.");if(!invalidFields.isEmpty())throw new IllegalArgumentException("Check "+LABELS.getOrDefault(invalidFields.iterator().next(),invalidFields.iterator().next()));if(draft.source==ScreenSpec.Source.WEB&&!draft.url.isBlank())draft.url=BrowserAddress.resolve(draft.url);draft.validate();saving=true;saveStarted=System.currentTimeMillis();pendingAction=action;closeAfterSave=close;MirrorClient.send("SAVE","id",original.id,"revision",original.revision,"spec",draft);feedback="Saving…";layoutRows();}catch(IllegalArgumentException e){saving=false;feedback=e.getMessage();focusProblem();}}

    private void focusProblem(){if(invalidFields.isEmpty())return;String field=invalidFields.iterator().next();for(int i=0;i<FIELDS.length;i++)if(Arrays.asList(FIELDS[i]).contains(field)){tab=i;break;}expanded.add(tab);rebuildWidgets();int offset=0;for(Row row:rows){if(row.field.equals(field)){scroll=offset;layoutRows();if(!row.widgets.isEmpty())setInitialFocus(row.widgets.getFirst());break;}offset+=rowHeight(row);}}

    private void saveThen(String action,Runnable next){if(saving)return;afterSave=next;save(action,false);if(!saving)afterSave=null;}

    private void openBrowser(boolean show,boolean controls,boolean close){
        if(saving)return;
        try{
            draft.url=BrowserAddress.resolve(draft.url);draft.source=ScreenSpec.Source.WEB;draft.link="";
            if(show){draft.live=true;}else if(!MirrorClient.isBrowserPublished(original.id))draft.live=false;
            draft.validate();
            long attempt=MirrorClient.shareAttempt();
            Runnable open=()->{
                try{
                    engine=MirrorClient.openBrowser(original.id,draft);browserAddress=draft.url;
                    if(show&&attempt==MirrorClient.shareAttempt())MirrorClient.showBrowserWhenReady(original.id,close);else if(show){draft.live=false;MirrorClient.send("DISMISS","id",original.id);feedback="";}else if(close)closeEditor();
                    feedback="Opening browser…";
                    if(controls)minecraft.setScreen(new BrowserControlScreen(this,original.id,engine));
                }catch(IllegalArgumentException e){feedback=e.getMessage();}
            };
            if(show||close)saveThen("",open);else open.run();
        }catch(IllegalArgumentException e){feedback=e.getMessage();openTab(0);}
    }
    public void showCurrentBrowser(boolean close){

        if(saving)return;draft.url=browserAddress();draft.source=ScreenSpec.Source.WEB;draft.live=true;

        long attempt=MirrorClient.shareAttempt();saveThen("",()->{if(attempt==MirrorClient.shareAttempt())MirrorClient.showBrowserWhenReady(original.id,close);else{draft.live=false;MirrorClient.send("DISMISS","id",original.id);}});

    }

    public void doneBrowsing(){draft.url=browserAddress();draft.live=MirrorClient.isBrowserPublished(original.id);if(unchanged())closeEditor();else save("",true);}

    public void makeBrowserPrivate(){MirrorClient.privateBrowser(original.id);draft.live=false;feedback="";}

    public TextureFrame previewFrame(){if(draft.source==ScreenSpec.Source.WEB&&MirrorClient.hasBrowser(original.id)){var frame=MirrorClient.browserFrame(original.id);return frame!=null&&frame.available()?frame:null;}if(draft.source!=original.spec.source||draft.source==ScreenSpec.Source.BLANK||draft.source==ScreenSpec.Source.TEXT)return null;if(draft.source==ScreenSpec.Source.MEDIA&&(!draft.playlist.equals(original.spec.playlist)||draft.item!=original.spec.item))return null;var view=MirrorClient.views.get(original.id);var frame=draft.source==ScreenSpec.Source.WEB&&MirrorClient.hasBrowser(original.id)?MirrorClient.browserFrame(original.id):view==null?MirrorClient.frames.get(original.id):MirrorClient.worldFrame(view);return frame!=null&&frame.available()?frame:null;}

    public void removed(){scene.close();}

    public void render(GuiGraphics g,int mx,int my,float delta){

        g.fill(0,0,width,height,0xf0202020);g.drawString(font,"Mirror • Hurtorius",10,12,0xffffffff);

        g.fill(7,bodyTop-1,10+formWidth+3,bodyBottom+1,0xff141414);int y=bodyTop-scroll,labelWidth=Math.min(138,Math.max(68,formWidth*40/100));

        for(Row row:rows){int h=rowHeight(row);if(y>=bodyTop&&y+h<=bodyBottom){if(row.kind==0){boolean invalid=invalidFields.contains(row.field);g.drawString(font,font.plainSubstrByWidth(row.label,labelWidth-5),10,y+9,invalid?0xffff7777:0xffdddddd);if(invalid){var box=row.widgets.getFirst();g.fill(box.getX()-1,y+2,box.getX()+box.getWidth()+1,y+3,0xffff7777);}}else if(row.kind==2)g.drawString(font,row.label,10,y+6,0xffaaaaaa);else if(row.kind==3)g.drawWordWrap(font,Component.literal(row.label),14,y+4,formWidth-10,0xffaaaaaa);}y+=h;}

        if(sidePreview)scene.render(g,font,previewConfig(),previewFrame(),"",previewX,bodyTop,width-previewX-10,bodyBottom-bodyTop);

        int content=contentHeight(),visible=bodyBottom-bodyTop;if(content>visible){int thumb=Math.max(8,visible*visible/content),at=bodyTop+(visible-thumb)*scroll/Math.max(1,content-visible);g.fill(formWidth+13,bodyTop,formWidth+15,bodyBottom,0xff444444);g.fill(formWidth+13,at,formWidth+15,at+thumb,0xffaaaaaa);}

        String status=!feedback.isBlank()?feedback:MirrorClient.status.startsWith("Importing")?MirrorClient.status:"";g.drawString(font,font.plainSubstrByWidth(status,width-20),10,height-43,0xffcccccc);super.render(g,mx,my,delta);

    }

    public boolean keyPressed(KeyEvent e){if(e.key()==266||e.key()==267){scroll+=(e.key()==267?1:-1)*Math.max(35,bodyBottom-bodyTop-35);layoutRows();return true;}return super.keyPressed(e);}

    public boolean mouseClicked(MouseButtonEvent e,boolean twice){if(super.mouseClicked(e,twice))return true;return sidePreview&&scene.beginDrag(e.x(),e.y(),e.button());}

    public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){return scene.drag(e.button(),dx,dy)||super.mouseDragged(e,dx,dy);}

    public boolean mouseReleased(MouseButtonEvent e){return scene.endDrag(e.button())||super.mouseReleased(e);}

    public boolean mouseScrolled(double x,double y,double dx,double dy){if(sidePreview&&scene.scroll(x,y,dy))return true;scroll-=(int)(dy*39);layoutRows();return true;}

    public void onClose(){if(saving){if(draft.source==ScreenSpec.Source.WEB&&afterSave!=null){afterSave=null;closeAfterSave=true;MirrorClient.privateBrowser(original.id);MirrorClient.send("DISMISS","id",original.id);}else feedback="Waiting for the save to finish";return;}cancelPendingBrowser();closeEditor();}

    private void closeEditor(){MirrorClient.editor=null;minecraft.setScreen(null);}

    public boolean isPauseScreen(){return false;}

    private static String pretty(String text){if(text.equals("BLANK"))return "Blank (black)";if(text.equals("WEB"))return "Web browser";if(text.equals("SHARE"))return "Screen share";if(text.equals("MOUNTED"))return "Fixed (manual wall placement)";if(text.equals("IRIS"))return "Expand";if(text.equals("MATERIALIZE"))return "Fade in";return capitalize(text.toLowerCase(Locale.ROOT).replace('_',' '));}

    private static String capitalize(String text){return Character.toUpperCase(text.charAt(0))+text.substring(1);}

    private static String size(long bytes){return bytes<1024*1024?bytes/1024+" KB":String.format(Locale.ROOT,"%.1f MB",bytes/(1024.0*1024));}

}

