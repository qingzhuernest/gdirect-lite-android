namespace GDirect;

// The window derives login availability from the same typed state used by request failures.
public sealed class AccountState
{
    public bool Connected {get;private set;}
    public void Authorized()=>Connected=true;
    public void Disconnect()=>Connected=false;
    public bool HandleFailure(Exception error){
        if(error is not AuthorizationFailure)return false;
        Disconnect();return true;
    }
}
