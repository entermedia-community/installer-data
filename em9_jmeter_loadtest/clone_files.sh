#!/bin/bash
if [ "$1" != "" ]; then
thedir=$1
     echo "Duplicate Massive in "${thedir}
     for f in "${thedir}"/*
        do
           filename=${f%%.*}
           fileext=${f#*.}
	   for i in {0..10}
             do
		cp -rp $f ${filename}-$i.${fileext}
	     done
      done
fi
