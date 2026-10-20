//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package Committer
import ROB._
import CDB._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage

class Committer extends Module{//unsigned
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val receive = Input(Bool())
    val cdb = Input(new CDB())

    val clear = Input(Bool())

    val rob  = Input(new ROB())
    val ready = Output(Bool())
    val halt = Output(Bool())

    val reg_change = Output(Bool())
    val reg_id = Output(UInt(5.W))
    val reg_val = Output(UInt(32.W))
    val reg_id = Output(SInt(32.W))

    val flush = Output(Bool())
    val flush_id = Output(UInt(32.W))
    val new_pc_val = Output(UInt(32.W))
  })
  val l = RegInit(0.U(3.W))
  val r = RegInit(0.U(3.W))
  val time = RegInit(0.U(8.W))
  val buffer= Reg(new ROB())
  val has_buffer = RegInit(false.B)
  val robs = Reg(Vec(8, new ROB()))
  val cur_rs = Reg(new RS())
  //update
  when (has_buffer){
    robs[r.asUInt]:=buffer
    has_buffer:=false.B
    r:=r+1.U
  }
  when (time=/=0.U){
    when (time===1.U){
        l:=l+1.U
    }
    time:= time-1.U
  }

//add
  val rd = io.valid&((r+1.U)=/=l)
  has_buffer:=rd
  io.ready:=rd
  buffer:=io.rob
  io.reg_change:=false.B
  when((time === 0.U)&(l=/=r)&robs(l.asUInt).ready){//commit
   when(robs(l.asUInt).type_ === 0.U){//alu
        io.reg_change:=true.B
        io.halt := (robs(l.asUInt).dest === 0xffffffff.U)
        io.reg_id := robs(l.asUInt).dest
        io.reg_val := robs(l.asUInt).value 
        io.reg_id := robs(l.asUInt).id
        //move the action of prod-overwriting to main module
   }.elsewhen(robs(l.asUInt).type_(31) === false.B){//store memory
   
   }.otherwise{//bru
      io.flush := robs(l.asUInt).value(0)
      io.flush_id := robs(l.asUInt).id  //need to clear all RS > id
      io.new_pc_vl := robs(l.asUInt).dest
   }
  }


  when(io.clear){//clear
    l:=0.U
    r:=0.U
    time:=0.U
  }
}
class Committer
{
private:
    ROB robs[maxk], buffer;
    int l, r, time;
    int debug_cnt;
    bool has_buffer;

public:
    int total, right; // for branch predicting
    Committer()
    {
        l = 0;
        r = 0;
        time = 0;
        debug_cnt = 0;
        has_buffer = 0;
        total = right = 0;
    }
    bool full()
    {
        return (r + 1) % maxk == l;
    }
    void add(ROB rob)
    {
        buffer = rob;
        has_buffer = 1;
    }
    void update()
    {
        if (has_buffer)
        {
            robs[r] = buffer;
            r = (r + 1) % maxk;
            has_buffer = 0;
        }
        if (time)
        {
            time--;
            if (time == 0)
            {
                l = (l + 1) % maxk;
            }
        }
    }
    pair<unsigned int, bool> read(int prod)
    {
        pair<unsigned, bool> ans;
        ans.second = 0;
        for (int id = l; id != r; id = (id + 1) % maxk)
        {
            if (robs[id].id == prod)
            {
                if (robs[id].ready)
                {
                    ans.first = robs[id].value;
                    ans.second = 1;
                }
                break;
            }
        }
        return ans;
    }

    unsigned long long find(int id, int L, int R)
    {
        unsigned long long val[4] = {0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF};
        for (int i = l; i != r; i = (i + 1) % maxk)
        {
            if (robs[i].id >= id)
            {
                break;
            }
            if (robs[i].type > 0 && robs[i].ready && robs[i].dest < R && robs[i].dest + robs[i].type > L)
            { // ready store operations that overlap
                for (int j = 0; j < robs[i].type; j++)
                {
                    int offset = robs[i].dest + j - L;
                    if (offset >= 0 && offset < R - L)
                    {
                        val[offset] = ((robs[i].value >> (8 * j)) & 255);
                    }
                }
            }
        }
        return val[0] | (val[1] << 16) | (val[2] << 32) | (val[3] << 48);
    }

    pair<int, int> Commit()
    {
        pair<int, int> ans = make_pair(0, 0);
        if (!time)
        {
            if (l != r && robs[l].ready)
            {
                time = 1;
                if (robs[l].type > 0)
                { // store memory
                    time = 2;
                    unsigned int value = robs[l].value;
                    for (int i = 0; i < robs[l].type; i++)
                    {
                        if (!memory.store(value & 255, robs[l].dest + i))
                        { // cache not hit
                            time = 3;
                        }
                        value >>= 8;
                    }
                    // cout<<"memory["<<cur_rb.dest<<"] changed to "<<cur_rb.value<<"(length "<<cur_rb.type<<")"<<endl;
                }
                else if (robs[l].type == 0)
                { // alu
                    if (robs[l].dest == (unsigned int)-1)
                    { // halt
                        std::cout << (reg[a0].val & 255) << std::endl;
                        halt = true;
                    }
                    else
                    {
                        reg[robs[l].dest].val = robs[l].value;
                        //  cout<<"reg["<<cur_rb.dest<<"] changed to "<<cur_rb.value<<endl;
                        if (reg[robs[l].dest].prod == robs[l].id)
                        {
                            reg[robs[l].dest].prod = -1;
                        }
                    }
                }
                else
                { // branch
                    total++;
                    if (robs[l].value & 1)
                    {                                               // needs to revert
                        ans = make_pair(-robs[l].id, robs[l].dest); // need to clear all RS > id
                    }
                    else
                    {
                        right++;
                    }
                    predictor.add(-robs[l].type - 1, robs[l].value);
                }
            }
        }
        return ans;
    }
    void receive(CDB cdb)
    {
        for (int id = l; id != r; id = (id + 1) % maxk)
        {
            if (robs[id].id == cdb.id && robs[id].ready == false)
            {
                if (cdb.dest != -2) // -2 is for branch operations, -1 is for halt
                {
                    robs[id].dest = cdb.dest;
                    robs[id].value = cdb.val;
                }
                else
                {
                    robs[id].value = robs[id].value * 2 + cdb.val;
                }
                robs[id].ready = true;
            }
        }
    }
    void clear()
    {
        l = r = time = 0;
    }
};