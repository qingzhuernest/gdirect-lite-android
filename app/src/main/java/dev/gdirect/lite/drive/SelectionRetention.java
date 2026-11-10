package dev.gdirect.lite.drive;

import java.util.LinkedHashMap;

/** Process-local selection retained by a platform view model, bound to an account and listing context. */
public final class SelectionRetention<T> {
    public final LinkedHashMap<String,T> items=new LinkedHashMap<>();
    private String context="";
    private boolean awaitingReload;
    public void bind(String context){if(!this.context.equals(context))clear();this.context=context;}
    public void recreate(String context){
        if(this.context.equals(context)&&!items.isEmpty())awaitingReload=true;else clear();
    }
    public boolean preserve(String context){return awaitingReload&&this.context.equals(context);}
    public void loaded(String context){if(this.context.equals(context))awaitingReload=false;}
    public void clear(){items.clear();context="";awaitingReload=false;}
}
