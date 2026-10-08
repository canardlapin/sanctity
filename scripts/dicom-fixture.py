#!/usr/bin/env python3
"""Synthetic, explicit-VR little-endian MR geometry fixture; no clinical data."""
import pathlib, struct, sys

SERIES = "1.2.826.0.1.3680043.10.543.1"
SOP = "1.2.840.10008.5.1.4.1.1.4"

def element(group, tag, vr, value):
    if isinstance(value, str):
        value = value.encode("ascii")
    if len(value) % 2:
        value += b"\0" if vr == "UI" else b" "
    head = struct.pack("<HH", group, tag) + vr.encode("ascii")
    if vr in ("OB", "OW", "SQ", "UN", "UT"):
        return head + b"\0\0" + struct.pack("<I", len(value)) + value
    return head + struct.pack("<H", len(value)) + value

def create(root):
    root.mkdir()
    for z in range(3):
        uid = SERIES + "." + str(z + 1)
        meta = b"".join([
            element(2,1,"OB",b"\0\1"), element(2,2,"UI",SOP), element(2,3,"UI",uid),
            element(2,16,"UI","1.2.840.10008.1.2.1"), element(2,18,"UI",SERIES+".99")])
        fields = [
            (0x0008,0x0008,"CS","ORIGINAL\\PRIMARY\\M\\ND"),
            (0x0008,0x0016,"UI",SOP),(0x0008,0x0018,"UI",uid),
            (0x0008,0x0020,"DA","20000101"),(0x0008,0x0030,"TM","120000"),
            (0x0008,0x0060,"CS","MR"),(0x0008,0x0070,"LO","SYNTHETIC"),
            (0x0008,0x103e,"LO","Synthetic T1w"),
            (0x0010,0x0010,"PN","SYNTHETIC"),(0x0010,0x0020,"LO","fixture"),
            (0x0018,0x0020,"CS","GR"),(0x0018,0x0021,"CS","SP"),(0x0018,0x0023,"CS","3D"),
            (0x0018,0x0050,"DS","2"),(0x0018,0x0080,"DS","2000"),(0x0018,0x0081,"DS","3"),
            (0x0018,0x0087,"DS","3"),(0x0018,0x0088,"DS","2"),(0x0018,0x1030,"LO","T1w"),
            (0x0018,0x1314,"DS","90"),(0x0020,0x000d,"UI",SERIES+".100"),
            (0x0020,0x000e,"UI",SERIES),(0x0020,0x0011,"IS","1"),(0x0020,0x0013,"IS",str(z+1)),
            (0x0020,0x0032,"DS",f"0\\0\\{z*2}"),(0x0020,0x0037,"DS","1\\0\\0\\0\\1\\0"),
            (0x0020,0x0052,"UI",SERIES+".200"),(0x0028,0x0002,"US",struct.pack("<H",1)),
            (0x0028,0x0004,"CS","MONOCHROME2"),(0x0028,0x0010,"US",struct.pack("<H",2)),
            (0x0028,0x0011,"US",struct.pack("<H",3)),(0x0028,0x0030,"DS","1\\1"),
            (0x0028,0x0100,"US",struct.pack("<H",16)),(0x0028,0x0101,"US",struct.pack("<H",16)),
            (0x0028,0x0102,"US",struct.pack("<H",15)),(0x0028,0x0103,"US",struct.pack("<H",0)),
            (0x0028,0x1052,"DS","0"),(0x0028,0x1053,"DS","1"),
            (0x7fe0,0x0010,"OW",struct.pack("<6H",*[z*100+y*10+x for y in range(2) for x in range(3)]))]
        data = b"\0"*128+b"DICM"+element(2,0,"UL",struct.pack("<I",len(meta)))+meta
        data += b"".join(element(*f) for f in sorted(fields))
        (root/f"slice-{z}.dcm").write_bytes(data)

def verify(path):
    data=path.read_bytes()
    assert struct.unpack_from("<I",data,0)[0]==348
    dims=struct.unpack_from("<8h",data,40)
    assert dims[1:4]==(3,2,3),dims
    datatype=struct.unpack_from("<h",data,70)[0]
    fmt={4:"h",512:"H",16:"f"}[datatype]
    offset=int(struct.unpack_from("<f",data,108)[0])
    slope,intercept=struct.unpack_from("<2f",data,112)
    if slope==0: slope=1
    assert struct.unpack_from("<h",data,254)[0]>0,"sform required"
    affine=[struct.unpack_from("<4f",data,280+16*r) for r in range(3)]
    values=struct.unpack_from("<18"+fmt,data,offset)
    for z in range(3):
        for y in range(2):
            for x in range(3):
                raw=values[(z*2+y)*3+x]*slope+intercept
                original_z=int(raw)//100; remainder=int(raw)%100
                original_y=remainder//10;original_x=remainder%10
                expected=(-original_x,-original_y,original_z*2)
                actual=[sum(row[i]*v for i,v in enumerate((x,y,z,1))) for row in affine]
                assert all(abs(a-b)<1e-5 for a,b in zip(actual,expected)),(raw,actual,expected)
    print("Synthetic DICOM -> NIfTI voxel values, scaling, dimensions and RAS world coordinates verified.")

if __name__=="__main__":
    {"create":create,"verify":verify}[sys.argv[1]](pathlib.Path(sys.argv[2]))
