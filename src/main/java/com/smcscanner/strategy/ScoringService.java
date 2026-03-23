package com.smcscanner.strategy;

import org.springframework.stereotype.Service;

@Service
public class ScoringService {
    public int scoreSetup(boolean sweep,boolean displacement,boolean fvg,boolean retest,boolean bos,boolean volumeSpike) {
        int s=0;
        if(sweep)s+=30; if(displacement)s+=25; if(fvg)s+=20; if(retest)s+=15; if(bos)s+=10; if(volumeSpike)s+=10;
        return Math.min(100,s);
    }
}
